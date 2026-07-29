package runtime

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/containerd/errdefs"
	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/image"
	"github.com/moby/moby/api/types/mount"
	"github.com/moby/moby/client"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/egresspolicy"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/supplychain"
)

var (
	// ErrWorkloadNotFound identifies an absent runtime-owned container.
	ErrWorkloadNotFound = errors.New("runtime workload not found")
	// ErrFenced identifies a stale or mismatched workload lease.
	ErrFenced = errors.New("runtime workload lease has been fenced")

	workloadIDPattern             = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$`)
	digestPattern                 = regexp.MustCompile(`@sha256:[a-f0-9]{64}$`)
	environmentPattern            = regexp.MustCompile(`^[A-Z_][A-Z0-9_]{0,127}$`)
	sensitiveEnvironmentFragments = []string{
		"API_KEY",
		"AUTHORIZATION",
		"CREDENTIAL",
		"PASSWORD",
		"PRIVATE_KEY",
		"SECRET",
		"TOKEN",
	}
)

// Engine manages only containers carrying this node's ownership labels.
type Engine struct {
	client            *client.Client
	config            config.Config
	admissionVerifier imageAdmissionVerifier
	sandbox           sandboxPolicy
	mcpSessions       *mcpSessionRegistry
}

type imageAdmissionVerifier interface {
	Verify(context.Context, string) (supplychain.Result, error)
}

// NewEngine connects to Docker Engine using its standard environment settings.
func NewEngine(cfg config.Config) (*Engine, error) {
	sandbox, err := loadSandboxPolicy(cfg)
	if err != nil {
		return nil, err
	}
	apiClient, err := client.New(
		client.FromEnv,
		client.WithUserAgent("open-simplepoint-tool-runtime/0.1.0"),
	)
	if err != nil {
		return nil, fmt.Errorf("create Docker Engine client: %w", err)
	}
	var verifier imageAdmissionVerifier
	if cfg.SupplyChainEnabled {
		verifier, err = supplychain.New(cfg)
		if err != nil {
			_ = apiClient.Close()
			return nil, err
		}
	}
	return &Engine{
		client:            apiClient,
		config:            cfg,
		admissionVerifier: verifier,
		sandbox:           sandbox,
		mcpSessions:       newMCPSessionRegistry(),
	}, nil
}

// Close releases Docker client resources.
func (e *Engine) Close() error {
	e.mcpSessions.closeAll()
	return e.client.Close()
}

// NodeStatus verifies Docker Engine connectivity and reports schedulable capacity.
func (e *Engine) NodeStatus(ctx context.Context) (NodeStatus, error) {
	ping, err := e.client.Ping(ctx, client.PingOptions{NegotiateAPIVersion: true})
	if err != nil {
		return NodeStatus{}, fmt.Errorf("ping Docker Engine: %w", err)
	}
	info, err := e.client.Info(ctx, client.InfoOptions{})
	if err != nil {
		return NodeStatus{}, fmt.Errorf("inspect Docker Engine capacity: %w", err)
	}
	sandbox, err := e.sandboxStatus(ctx)
	if err != nil {
		return NodeStatus{}, err
	}
	containers, err := e.client.ContainerList(ctx, client.ContainerListOptions{
		Filters: make(client.Filters).Add("label", labelManaged+"=true"),
	})
	if err != nil {
		return NodeStatus{}, fmt.Errorf("list managed workloads: %w", err)
	}
	images, err := e.client.ImageList(ctx, client.ImageListOptions{})
	if err != nil {
		return NodeStatus{}, fmt.Errorf("list cached images: %w", err)
	}
	runningWorkloads := 0
	for _, workload := range containers.Items {
		if workload.Labels[labelNodeID] == e.config.NodeID {
			runningWorkloads++
		}
	}
	maxWorkloadMemory := min(e.config.MaxMemoryBytes, info.Info.MemTotal)
	maxWorkloadNanoCPUs := min(
		e.config.MaxNanoCPUs,
		int64(info.Info.NCPU)*1_000_000_000,
	)
	return NodeStatus{
		NodeID:           e.config.NodeID,
		DisplayName:      info.Info.Name,
		Status:           "UP",
		EngineAPIVersion: ping.APIVersion,
		EngineOSType:     ping.OSType,
		CPUCores:         info.Info.NCPU,
		MemoryBytes:      info.Info.MemTotal,
		MaxWorkloads:     e.config.MaxWorkloads,
		RunningWorkloads: runningWorkloads,
		CachedImageDigests: cachedImageDigests(
			images.Items,
			e.config.MaxReportedImageDigests,
		),
		MaxWorkloadMemoryBytes:      maxWorkloadMemory,
		MaxWorkloadNanoCPUs:         maxWorkloadNanoCPUs,
		MaxWorkloadPidsLimit:        e.config.MaxPidsLimit,
		RequireImageDigest:          e.config.RequireDigest,
		RequireMCPLabels:            e.config.RequireMCPLabels,
		AllowBridgeNetwork:          e.config.AllowBridgeNetwork,
		AllowEgressNetwork:          e.config.EgressEnabled,
		RequireSupplyChainAdmission: e.config.SupplyChainEnabled,
		SeccompEnforced:             sandbox.seccompEnforced,
		SeccompProfileHash:          e.sandbox.seccompHash,
		AppArmorEnforced: e.config.AppArmorEnabled &&
			sandbox.apparmorAvailable &&
			e.config.AppArmorProfile != "",
		AppArmorProfile: e.config.AppArmorProfile,
		CheckedAt:       time.Now().UTC(),
	}, nil
}

func cachedImageDigests(images []image.Summary, limit int) []string {
	digests := make(map[string]struct{})
	for _, cached := range images {
		for _, repoDigest := range cached.RepoDigests {
			_, digest, found := strings.Cut(repoDigest, "@")
			if found && strings.HasPrefix(digest, "sha256:") &&
				len(digest) == len("sha256:")+64 {
				digests[digest] = struct{}{}
			}
		}
	}
	result := make([]string, 0, len(digests))
	for digest := range digests {
		result = append(result, digest)
	}
	sort.Strings(result)
	if len(result) > limit {
		return result[:limit]
	}
	return result
}

// Prepare pulls a missing image by digest and validates OCI/MCP metadata.
func (e *Engine) Prepare(ctx context.Context, image string) (ImageStatus, error) {
	if err := e.validateImageReference(image); err != nil {
		return ImageStatus{}, err
	}
	admission := supplychain.Result{}
	var err error
	if e.config.SupplyChainEnabled {
		if e.admissionVerifier == nil {
			return ImageStatus{}, errors.New(
				"OCI supply-chain admission verifier is unavailable",
			)
		}
		admission, err = e.admissionVerifier.Verify(ctx, image)
		if err != nil {
			return ImageStatus{}, fmt.Errorf(
				"verify OCI supply chain: %w",
				err,
			)
		}
		if !admission.Admitted ||
			!admission.SignatureVerified ||
			!admission.SBOMVerified {
			reason := strings.TrimSpace(admission.Reason)
			if reason == "" {
				reason = "image did not satisfy the admission policy"
			}
			return ImageStatus{}, fmt.Errorf(
				"OCI supply-chain admission denied: %s",
				reason,
			)
		}
	}
	inspect, err := e.client.ImageInspect(ctx, image)
	if err != nil {
		pull, pullErr := e.client.ImagePull(ctx, image, client.ImagePullOptions{})
		if pullErr != nil {
			return ImageStatus{}, fmt.Errorf("pull OCI image: %w", pullErr)
		}
		defer pull.Close()
		if waitErr := pull.Wait(ctx); waitErr != nil {
			return ImageStatus{}, fmt.Errorf("wait for OCI image pull: %w", waitErr)
		}
		inspect, err = e.client.ImageInspect(ctx, image)
		if err != nil {
			return ImageStatus{}, fmt.Errorf("inspect pulled OCI image: %w", err)
		}
	}
	labels := map[string]string{}
	if inspect.Config != nil {
		for key, value := range inspect.Config.Labels {
			labels[key] = value
		}
	}
	transport := labels[labelMCPTransport]
	protocol := labels[labelMCPProtocol]
	if e.config.RequireMCPLabels {
		if labels[labelOCIImageTitle] == "" {
			return ImageStatus{}, errors.New("OCI image title label is required")
		}
		if transport != "streamable-http" && transport != "stdio" {
			return ImageStatus{}, errors.New("OCI image MCP transport label is invalid")
		}
		if protocol != e.config.RequiredProtocolVersion {
			return ImageStatus{}, errors.New("OCI image MCP protocol version is incompatible")
		}
	}
	return ImageStatus{
		Reference:           image,
		ImageID:             inspect.ID,
		RepoDigests:         append([]string(nil), inspect.RepoDigests...),
		Labels:              labels,
		MCPTransport:        transport,
		ProtocolVersion:     protocol,
		SupplyChainAdmitted: admission.Admitted,
		SignatureVerified:   admission.SignatureVerified,
		SBOMVerified:        admission.SBOMVerified,
		AdmissionPolicyHash: admission.PolicyHash,
	}, nil
}

// Start validates, creates, and starts one sandboxed OCI workload.
func (e *Engine) Start(ctx context.Context, request StartRequest) (WorkloadStatus, error) {
	request, err := e.normalize(request)
	if err != nil {
		return WorkloadStatus{}, err
	}
	existing, err := e.status(ctx, request.WorkloadID)
	if err == nil {
		switch {
		case existing.FencingToken > request.FencingToken:
			return WorkloadStatus{}, ErrFenced
		case existing.FencingToken == request.FencingToken &&
			(existing.LeaseID != request.LeaseID ||
				existing.ExecutionID != request.ExecutionID):
			return WorkloadStatus{}, ErrFenced
		case existing.FencingToken == request.FencingToken &&
			existing.State == "created":
			if _, err = e.client.ContainerStart(
				ctx,
				existing.ContainerID,
				client.ContainerStartOptions{},
			); err != nil {
				return WorkloadStatus{}, fmt.Errorf(
					"resume created workload: %w",
					err,
				)
			}
			return e.Status(
				ctx,
				request.WorkloadID,
				request.LeaseID,
				request.FencingToken,
			)
		case existing.FencingToken == request.FencingToken:
			return existing, nil
		default:
			if _, err = e.client.ContainerRemove(
				ctx,
				existing.ContainerID,
				client.ContainerRemoveOptions{
					Force:         true,
					RemoveVolumes: true,
				},
			); err != nil {
				return WorkloadStatus{}, fmt.Errorf(
					"remove fenced workload generation: %w",
					err,
				)
			}
			if cleanupErr := e.cleanupSecrets(
				existing.WorkloadID,
				existing.FencingToken,
			); cleanupErr != nil {
				return WorkloadStatus{}, cleanupErr
			}
		}
	} else if !errors.Is(err, ErrWorkloadNotFound) {
		return WorkloadStatus{}, err
	}
	imageStatus, err := e.Prepare(ctx, request.Image)
	if err != nil {
		return WorkloadStatus{}, err
	}
	securityOptions, _, err := e.workloadSecurityOptions(ctx)
	if err != nil {
		return WorkloadStatus{}, err
	}
	deadline := time.Now().UTC().Add(time.Duration(request.TimeoutSeconds) * time.Second)
	labels := map[string]string{
		labelManaged:      "true",
		labelNodeID:       e.config.NodeID,
		labelWorkloadID:   request.WorkloadID,
		labelExecutionID:  request.ExecutionID,
		labelTenantID:     request.TenantID,
		labelLeaseID:      request.LeaseID,
		labelFencingToken: strconv.FormatInt(request.FencingToken, 10),
		labelDeadline:     deadline.Format(time.RFC3339Nano),
		labelMCPTransport: imageStatus.MCPTransport,
	}
	secretSubpath, err := e.prepareSecrets(request)
	if err != nil {
		return WorkloadStatus{}, err
	}
	workloadEnvironment := environment(request.Environment)
	workloadMounts := []mount.Mount{}
	workloadNetwork := container.NetworkMode(request.NetworkMode)
	if request.NetworkMode == networkEgress {
		token, _, mintErr := egresspolicy.Mint(
			request.WorkloadID,
			deadline,
			request.EgressAllowlist,
			e.config.EgressSigningKey,
			e.config.MaxEgressHosts,
		)
		if mintErr != nil {
			_ = e.cleanupSecrets(request.WorkloadID, request.FencingToken)
			return WorkloadStatus{}, mintErr
		}
		proxyURL, proxyErr := workloadProxyURL(
			e.config.EgressProxyURL,
			request.WorkloadID,
			token,
		)
		if proxyErr != nil {
			_ = e.cleanupSecrets(request.WorkloadID, request.FencingToken)
			return WorkloadStatus{}, proxyErr
		}
		workloadEnvironment = append(
			workloadEnvironment,
			"HTTP_PROXY="+proxyURL,
			"HTTPS_PROXY="+proxyURL,
			"NO_PROXY=localhost,127.0.0.1,::1",
			"http_proxy="+proxyURL,
			"https_proxy="+proxyURL,
			"no_proxy=localhost,127.0.0.1,::1",
		)
		workloadNetwork = container.NetworkMode(e.config.EgressNetworkName)
	}
	if secretSubpath != "" {
		workloadEnvironment = append(
			workloadEnvironment,
			secretDirectoryEnv+"="+e.config.SecretMountTarget,
		)
		workloadMounts = append(workloadMounts, mount.Mount{
			Type:     mount.TypeVolume,
			Source:   e.config.SecretVolumeName,
			Target:   e.config.SecretMountTarget,
			ReadOnly: true,
			VolumeOptions: &mount.VolumeOptions{
				NoCopy:  true,
				Subpath: secretSubpath,
			},
		})
	}
	initProcess := true
	pidsLimit := request.PidsLimit
	created, err := e.client.ContainerCreate(ctx, client.ContainerCreateOptions{
		Config: &container.Config{
			Image:     request.Image,
			User:      e.config.DefaultWorkloadUser,
			Cmd:       append([]string(nil), request.Command...),
			Env:       workloadEnvironment,
			Labels:    labels,
			OpenStdin: imageStatus.MCPTransport == "stdio",
			StdinOnce: false,
		},
		HostConfig: &container.HostConfig{
			AutoRemove:     false,
			CapDrop:        []string{"ALL"},
			Init:           &initProcess,
			NetworkMode:    workloadNetwork,
			Privileged:     false,
			ReadonlyRootfs: true,
			SecurityOpt:    securityOptions,
			Tmpfs: map[string]string{
				"/tmp": fmt.Sprintf(
					"rw,noexec,nosuid,nodev,size=%d",
					e.config.DefaultTmpfsSizeBytes,
				),
			},
			Mounts: workloadMounts,
			Resources: container.Resources{
				Memory:    request.MemoryBytes,
				NanoCPUs:  request.NanoCPUs,
				PidsLimit: &pidsLimit,
			},
		},
		Name: e.containerName(request.WorkloadID),
	})
	if err != nil {
		_ = e.cleanupSecrets(request.WorkloadID, request.FencingToken)
		return WorkloadStatus{}, fmt.Errorf("create sandboxed workload: %w", err)
	}
	if _, err = e.client.ContainerStart(
		ctx,
		created.ID,
		client.ContainerStartOptions{},
	); err != nil {
		_, _ = e.client.ContainerRemove(
			context.WithoutCancel(ctx),
			created.ID,
			client.ContainerRemoveOptions{Force: true},
		)
		_ = e.cleanupSecrets(request.WorkloadID, request.FencingToken)
		return WorkloadStatus{}, fmt.Errorf("start sandboxed workload: %w", err)
	}
	return e.Status(
		ctx,
		request.WorkloadID,
		request.LeaseID,
		request.FencingToken,
	)
}

// Status returns one runtime-owned workload.
func (e *Engine) Status(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
) (WorkloadStatus, error) {
	status, err := e.status(ctx, workloadID)
	if err != nil {
		return WorkloadStatus{}, err
	}
	if status.LeaseID != leaseID || status.FencingToken != fencingToken {
		return WorkloadStatus{}, ErrFenced
	}
	return status, nil
}

func (e *Engine) status(
	ctx context.Context,
	workloadID string,
) (WorkloadStatus, error) {
	if !workloadIDPattern.MatchString(workloadID) {
		return WorkloadStatus{}, errors.New("workload ID is invalid")
	}
	inspect, err := e.client.ContainerInspect(
		ctx,
		e.containerName(workloadID),
		client.ContainerInspectOptions{},
	)
	if err != nil {
		if errdefs.IsNotFound(err) {
			return WorkloadStatus{}, ErrWorkloadNotFound
		}
		return WorkloadStatus{}, fmt.Errorf("inspect workload: %w", err)
	}
	value := inspect.Container
	if value.Config == nil ||
		value.Config.Labels[labelManaged] != "true" ||
		value.Config.Labels[labelNodeID] != e.config.NodeID ||
		value.Config.Labels[labelWorkloadID] != workloadID {
		return WorkloadStatus{}, errors.New("container is not owned by this runtime node")
	}
	fencingToken, err := strconv.ParseInt(
		value.Config.Labels[labelFencingToken],
		10,
		64,
	)
	if err != nil || fencingToken <= 0 {
		return WorkloadStatus{}, errors.New("container workload fence is invalid")
	}
	status := WorkloadStatus{
		WorkloadID:   workloadID,
		LeaseID:      value.Config.Labels[labelLeaseID],
		FencingToken: fencingToken,
		ExecutionID:  value.Config.Labels[labelExecutionID],
		TenantID:     value.Config.Labels[labelTenantID],
		ContainerID:  value.ID,
		Image:        value.Config.Image,
		RuntimeNode:  e.config.NodeID,
	}
	if parsed, parseErr := time.Parse(
		time.RFC3339Nano,
		value.Config.Labels[labelDeadline],
	); parseErr == nil {
		status.Deadline = parsed
	}
	if value.State != nil {
		status.State = string(value.State.Status)
		status.StartedAt = value.State.StartedAt
		status.FinishedAt = value.State.FinishedAt
		status.ExitCode = value.State.ExitCode
		if value.State.Health != nil {
			status.Health = string(value.State.Health.Status)
		}
	}
	return status, nil
}

// Stop stops one owned workload without deleting its audit-visible state.
func (e *Engine) Stop(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
) (WorkloadStatus, error) {
	status, err := e.Status(ctx, workloadID, leaseID, fencingToken)
	if err != nil {
		return WorkloadStatus{}, err
	}
	e.mcpSessions.closeWorkload(workloadID)
	timeout := defaultStopTimeout
	if _, err = e.client.ContainerStop(
		ctx,
		status.ContainerID,
		client.ContainerStopOptions{Timeout: &timeout},
	); err != nil {
		return WorkloadStatus{}, fmt.Errorf("stop workload: %w", err)
	}
	if err = e.cleanupSecrets(workloadID, fencingToken); err != nil {
		return WorkloadStatus{}, err
	}
	return e.Status(ctx, workloadID, leaseID, fencingToken)
}

// Delete removes one stopped runtime-owned workload.
func (e *Engine) Delete(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
) error {
	status, err := e.Status(ctx, workloadID, leaseID, fencingToken)
	if err != nil {
		return err
	}
	if status.State == "running" || status.State == "restarting" {
		return errors.New("running workload must be stopped before deletion")
	}
	e.mcpSessions.closeWorkload(workloadID)
	if _, err = e.client.ContainerRemove(
		ctx,
		status.ContainerID,
		client.ContainerRemoveOptions{RemoveVolumes: true},
	); err != nil {
		return fmt.Errorf("delete workload: %w", err)
	}
	return e.cleanupSecrets(workloadID, fencingToken)
}

func (e *Engine) normalize(request StartRequest) (StartRequest, error) {
	if !workloadIDPattern.MatchString(request.WorkloadID) {
		return StartRequest{}, errors.New("workload ID is invalid")
	}
	if !workloadIDPattern.MatchString(request.LeaseID) ||
		request.FencingToken <= 0 {
		return StartRequest{}, errors.New("workload lease fence is invalid")
	}
	if !workloadIDPattern.MatchString(request.ExecutionID) {
		return StartRequest{}, errors.New("execution ID is invalid")
	}
	if request.TenantID != "" && !workloadIDPattern.MatchString(request.TenantID) {
		return StartRequest{}, errors.New("tenant ID is invalid")
	}
	if len(request.Command) > maximumCommandItems {
		return StartRequest{}, errors.New("workload command contains too many arguments")
	}
	if len(request.Environment) > maximumEnvironment {
		return StartRequest{}, errors.New("workload environment contains too many entries")
	}
	for key, value := range request.Environment {
		if !environmentPattern.MatchString(key) || len(value) > maximumEnvironmentKV {
			return StartRequest{}, errors.New("workload environment is invalid")
		}
		if isSensitiveEnvironment(key) {
			return StartRequest{}, errors.New(
				"workload secrets must not be passed through environment variables",
			)
		}
		if isReservedEnvironment(key) {
			return StartRequest{}, errors.New(
				"workload environment contains a reserved runtime variable",
			)
		}
	}
	if len(request.Secrets) > e.config.MaxSecretFiles {
		return StartRequest{}, errors.New("workload contains too many secret files")
	}
	secretNames := make(map[string]struct{}, len(request.Secrets))
	var secretTotalBytes int64
	for _, secret := range request.Secrets {
		if !workloadIDPattern.MatchString(secret.Name) {
			return StartRequest{}, errors.New("workload secret file name is invalid")
		}
		if _, exists := secretNames[secret.Name]; exists {
			return StartRequest{}, errors.New("workload secret file names must be unique")
		}
		secretNames[secret.Name] = struct{}{}
		size := int64(len([]byte(secret.Value)))
		if size == 0 || size > e.config.MaxSecretFileBytes {
			return StartRequest{}, errors.New("workload secret file size is invalid")
		}
		secretTotalBytes += size
		if secretTotalBytes > e.config.MaxSecretTotalBytes {
			return StartRequest{}, errors.New("workload secret files exceed the total size limit")
		}
	}
	sort.Slice(request.Secrets, func(left, right int) bool {
		return request.Secrets[left].Name < request.Secrets[right].Name
	})
	if request.MemoryBytes == 0 {
		request.MemoryBytes = e.config.DefaultMemoryBytes
	}
	if request.MemoryBytes < minimumMemoryBytes ||
		request.MemoryBytes > e.config.MaxMemoryBytes {
		return StartRequest{}, errors.New("workload memory limit is invalid")
	}
	if request.NanoCPUs == 0 {
		request.NanoCPUs = e.config.DefaultNanoCPUs
	}
	if request.NanoCPUs <= 0 || request.NanoCPUs > e.config.MaxNanoCPUs {
		return StartRequest{}, errors.New("workload CPU limit is invalid")
	}
	if request.PidsLimit == 0 {
		request.PidsLimit = e.config.DefaultPidsLimit
	}
	if request.PidsLimit <= 0 || request.PidsLimit > e.config.MaxPidsLimit {
		return StartRequest{}, errors.New("workload PID limit is invalid")
	}
	if request.TimeoutSeconds <= 0 || request.TimeoutSeconds > 24*60*60 {
		return StartRequest{}, errors.New("workload timeout must be between 1 second and 24 hours")
	}
	request.NetworkMode = strings.ToLower(strings.TrimSpace(request.NetworkMode))
	if request.NetworkMode == "" {
		request.NetworkMode = networkNone
	}
	if request.NetworkMode != networkNone &&
		!(request.NetworkMode == networkBridge && e.config.AllowBridgeNetwork) &&
		!(request.NetworkMode == networkEgress && e.config.EgressEnabled) {
		return StartRequest{}, errors.New("workload network mode is not allowed")
	}
	if request.NetworkMode == networkEgress {
		normalized, err := egresspolicy.NormalizeHosts(
			request.EgressAllowlist,
			e.config.MaxEgressHosts,
		)
		if err != nil {
			return StartRequest{}, err
		}
		request.EgressAllowlist = normalized
	} else if len(request.EgressAllowlist) > 0 {
		return StartRequest{}, errors.New(
			"workload egress hosts require egress network mode",
		)
	}
	return request, nil
}

func (e *Engine) prepareSecrets(request StartRequest) (string, error) {
	if len(request.Secrets) == 0 {
		return "", nil
	}
	subpath := e.secretSubpath(request.WorkloadID, request.FencingToken)
	directory, err := e.secretPath(subpath)
	if err != nil {
		return "", err
	}
	if err = os.RemoveAll(directory); err != nil && !os.IsNotExist(err) {
		return "", fmt.Errorf("clear stale workload secret directory: %w", err)
	}
	if err = os.MkdirAll(directory, 0o700); err != nil {
		return "", fmt.Errorf("create workload secret directory: %w", err)
	}
	for _, secret := range request.Secrets {
		path := filepath.Join(directory, secret.Name)
		file, openErr := os.OpenFile(
			path,
			os.O_WRONLY|os.O_CREATE|os.O_EXCL,
			0o400,
		)
		if openErr != nil {
			_ = os.RemoveAll(directory)
			return "", fmt.Errorf("create workload secret file: %w", openErr)
		}
		_, writeErr := file.WriteString(secret.Value)
		if writeErr == nil {
			writeErr = file.Sync()
		}
		closeErr := file.Close()
		if writeErr != nil || closeErr != nil {
			_ = os.RemoveAll(directory)
			if writeErr != nil {
				return "", fmt.Errorf("write workload secret file: %w", writeErr)
			}
			return "", fmt.Errorf("close workload secret file: %w", closeErr)
		}
	}
	return subpath, nil
}

func (e *Engine) cleanupSecrets(workloadID string, fencingToken int64) error {
	directory, err := e.secretPath(e.secretSubpath(workloadID, fencingToken))
	if err != nil {
		return err
	}
	if err = os.RemoveAll(directory); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("remove workload secret directory: %w", err)
	}
	return nil
}

func (e *Engine) secretSubpath(workloadID string, fencingToken int64) string {
	return filepath.Join(
		strings.ToLower(strings.ReplaceAll(e.config.NodeID, "_", "-")),
		strings.ToLower(workloadID)+"-"+strconv.FormatInt(fencingToken, 10),
	)
}

func (e *Engine) secretPath(subpath string) (string, error) {
	root := filepath.Clean(e.config.SecretRoot)
	target := filepath.Clean(filepath.Join(root, subpath))
	relative, err := filepath.Rel(root, target)
	if err != nil ||
		relative == "." ||
		relative == ".." ||
		strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", errors.New("workload secret path escapes the configured root")
	}
	return target, nil
}

func (e *Engine) validateImageReference(image string) error {
	image = strings.TrimSpace(image)
	if image == "" || len(image) > 512 {
		return errors.New("OCI image reference is invalid")
	}
	if e.config.RequireDigest && !digestPattern.MatchString(image) {
		return errors.New("OCI image reference must be pinned by sha256 digest")
	}
	registry := imageRegistry(image)
	if _, allowed := e.config.AllowedRegistries[registry]; !allowed {
		return fmt.Errorf("OCI registry %q is not allowed", registry)
	}
	return nil
}

func (e *Engine) containerName(workloadID string) string {
	node := strings.ToLower(strings.ReplaceAll(e.config.NodeID, "_", "-"))
	return e.config.Namespace + node + "-" + strings.ToLower(workloadID)
}

func imageRegistry(image string) string {
	name := strings.SplitN(image, "@", 2)[0]
	first, _, found := strings.Cut(name, "/")
	if !found || (!strings.Contains(first, ".") &&
		!strings.Contains(first, ":") &&
		first != "localhost") {
		return "docker.io"
	}
	return strings.ToLower(first)
}

func environment(values map[string]string) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]string, 0, len(keys))
	for _, key := range keys {
		result = append(result, key+"="+values[key])
	}
	return result
}

func isSensitiveEnvironment(key string) bool {
	for _, fragment := range sensitiveEnvironmentFragments {
		if strings.Contains(key, fragment) {
			return true
		}
	}
	return false
}

func isReservedEnvironment(key string) bool {
	switch strings.ToUpper(key) {
	case secretDirectoryEnv,
		"HTTP_PROXY",
		"HTTPS_PROXY",
		"ALL_PROXY",
		"NO_PROXY":
		return true
	default:
		return false
	}
}

func workloadProxyURL(
	value string,
	workloadID string,
	token string,
) (string, error) {
	parsed, err := url.Parse(value)
	if err != nil {
		return "", errors.New("workload egress proxy URL is invalid")
	}
	parsed.User = url.UserPassword(workloadID, token)
	return parsed.String(), nil
}
