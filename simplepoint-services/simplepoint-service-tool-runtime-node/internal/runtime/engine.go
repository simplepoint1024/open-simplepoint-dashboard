package runtime

import (
	"context"
	"encoding/json"
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
	"github.com/moby/moby/api/types/network"
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
	environmentPattern            = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]{0,127}$`)
	managedVolumePattern          = regexp.MustCompile(`^open-simplepoint-managed-[a-f0-9]{40}$`)
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

type workloadLauncherConfig struct {
	Command           []string          `json:"command"`
	SecretEnvironment map[string]string `json:"secretEnvironment"`
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

// Prepare resolves and pulls an allowed image for control-plane publication.
func (e *Engine) Prepare(ctx context.Context, image string) (ImageStatus, error) {
	if err := e.validateResolvableImageReference(image); err != nil {
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
	if err := e.validateImageReference(request.Image); err != nil {
		return WorkloadStatus{}, err
	}
	imageStatus, err := e.Prepare(ctx, request.Image)
	if err != nil {
		return WorkloadStatus{}, err
	}
	if err = e.ensureManagedStorage(ctx, request); err != nil {
		return WorkloadStatus{}, err
	}
	securityOptions, _, err := e.workloadSecurityOptions(ctx)
	if err != nil {
		return WorkloadStatus{}, err
	}
	deadline := time.Now().UTC().Add(time.Duration(request.TimeoutSeconds) * time.Second)
	transport := request.Transport
	if transport == "" {
		transport = imageStatus.MCPTransport
	}
	if transport != "stdio" && transport != "streamable-http" {
		return WorkloadStatus{}, errors.New("workload MCP transport is invalid")
	}
	labels := map[string]string{
		labelManaged:        "true",
		labelNodeID:         e.config.NodeID,
		labelWorkloadID:     request.WorkloadID,
		labelExecutionID:    request.ExecutionID,
		labelTenantID:       request.TenantID,
		labelLeaseID:        request.LeaseID,
		labelFencingToken:   strconv.FormatInt(request.FencingToken, 10),
		labelDeadline:       deadline.Format(time.RFC3339Nano),
		labelMCPTransport:   transport,
		labelMCPProtocol:    e.config.RequiredProtocolVersion,
		labelMCPSessionMode: request.SessionMode,
		labelMCPMaxSessions: strconv.Itoa(request.MaxSessions),
		labelMCPPort:        strconv.Itoa(request.ContainerPort),
		labelMCPPath:        request.TransportPath,
		labelSandboxProfile: request.SandboxProfile,
	}
	launcherConfig, err := e.launcherConfig(ctx, request)
	if err != nil {
		return WorkloadStatus{}, err
	}
	secretSubpath, err := e.prepareSecrets(request, launcherConfig)
	if err != nil {
		return WorkloadStatus{}, err
	}
	workloadEnvironment := environment(request.Environment)
	workloadMounts := []mount.Mount{}
	workloadTmpfs := map[string]string{
		"/tmp": tmpfsOptions(e.config.DefaultTmpfsSizeBytes, false),
	}
	for _, storage := range request.Storage {
		switch storage.Type {
		case "TMPFS", "EPHEMERAL":
			workloadTmpfs[storage.TargetPath] = tmpfsOptions(
				storage.SizeBytes,
				storage.ReadOnly,
			)
		default:
			workloadMounts = append(workloadMounts, mount.Mount{
				Type:     mount.TypeVolume,
				Source:   storage.Source,
				Target:   storage.TargetPath,
				ReadOnly: storage.ReadOnly,
				VolumeOptions: &mount.VolumeOptions{
					NoCopy: true,
				},
			})
		}
	}
	workloadNetwork := container.NetworkMode(request.NetworkMode)
	additionalNetworks := []string{}
	if transport == "streamable-http" {
		workloadNetwork = container.NetworkMode(e.config.TransportNetworkName)
	}
	if request.NetworkMode == networkTCPEgress ||
		request.NetworkMode == networkInternal {
		routes := e.config.TCPEgressRoutes
		if request.NetworkMode == networkInternal {
			routes = e.config.InternalServiceRoutes
		}
		networks := routeNetworks(request.EgressAllowlist, routes)
		if transport == "streamable-http" {
			additionalNetworks = append(additionalNetworks, networks...)
		} else {
			workloadNetwork = container.NetworkMode(networks[0])
			additionalNetworks = append(additionalNetworks, networks[1:]...)
		}
	}
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
		if transport == "streamable-http" {
			additionalNetworks = append(
				additionalNetworks,
				e.config.EgressNetworkName,
			)
		} else {
			workloadNetwork = container.NetworkMode(e.config.EgressNetworkName)
		}
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
	containerEntrypoint := append([]string(nil), request.Entrypoint...)
	containerCommand := append(
		append([]string(nil), request.Command...),
		request.Arguments...,
	)
	if launcherConfig != nil {
		workloadEnvironment = append(
			workloadEnvironment,
			"SIMPLEPOINT_LAUNCHER_CONFIG="+
				e.config.SecretMountTarget+"/.runtime-launcher.json",
		)
		workloadMounts = append(workloadMounts, mount.Mount{
			Type:     mount.TypeVolume,
			Source:   e.config.SecretVolumeName,
			Target:   e.config.LauncherMountTarget,
			ReadOnly: true,
			VolumeOptions: &mount.VolumeOptions{
				NoCopy:  true,
				Subpath: launcherVolumeSubpath,
			},
		})
		containerEntrypoint = []string{
			e.config.LauncherMountTarget + "/launcher",
		}
		containerCommand = nil
	}
	initProcess := true
	pidsLimit := request.PidsLimit
	workloadUser := e.config.DefaultWorkloadUser
	if request.ProcessUserMode == "IMAGE_DEFAULT" {
		workloadUser = ""
	}
	created, err := e.client.ContainerCreate(ctx, client.ContainerCreateOptions{
		Config: &container.Config{
			Image:      request.Image,
			User:       workloadUser,
			Entrypoint: containerEntrypoint,
			Cmd:        containerCommand,
			WorkingDir: request.WorkingDirectory,
			Env:        workloadEnvironment,
			Labels:     labels,
			OpenStdin:  transport == "stdio",
			StdinOnce:  false,
		},
		HostConfig: &container.HostConfig{
			AutoRemove:     false,
			CapDrop:        []string{"ALL"},
			Init:           &initProcess,
			NetworkMode:    workloadNetwork,
			Privileged:     false,
			ReadonlyRootfs: true,
			SecurityOpt:    securityOptions,
			Tmpfs:          workloadTmpfs,
			Mounts:         workloadMounts,
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
	for _, networkName := range additionalNetworks {
		if _, err = e.client.NetworkConnect(
			ctx,
			networkName,
			client.NetworkConnectOptions{
				Container:      created.ID,
				EndpointConfig: &network.EndpointSettings{},
			},
		); err != nil {
			_, _ = e.client.ContainerRemove(
				context.WithoutCancel(ctx),
				created.ID,
				client.ContainerRemoveOptions{Force: true},
			)
			_ = e.cleanupSecrets(request.WorkloadID, request.FencingToken)
			return WorkloadStatus{}, fmt.Errorf(
				"connect workload policy network: %w",
				err,
			)
		}
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

// ensureManagedStorage creates platform-owned named volumes before workload
// creation and prepares their root for the fixed non-root workload identity.
// The one-shot container executes only the Runtime's mounted static launcher;
// it never invokes code from the third-party image.
func (e *Engine) ensureManagedStorage(
	ctx context.Context,
	request StartRequest,
) error {
	createdVolumes := []string{}
	for _, storage := range request.Storage {
		if storage.Type == "TMPFS" || storage.Type == "EPHEMERAL" {
			continue
		}
		inspection, err := e.client.VolumeInspect(
			ctx,
			storage.Source,
			client.VolumeInspectOptions{},
		)
		created := false
		if err != nil {
			if !errdefs.IsNotFound(err) {
				return fmt.Errorf("inspect managed storage: %w", err)
			}
			if _, err = e.client.VolumeCreate(ctx, client.VolumeCreateOptions{
				Name: storage.Source,
				Labels: map[string]string{
					labelManagedStorage: "true",
					labelNodeID:         e.config.NodeID,
				},
			}); err != nil {
				return fmt.Errorf("create managed storage: %w", err)
			}
			created = true
		} else if inspection.Volume.Labels[labelManagedStorage] != "true" {
			return errors.New(
				"managed storage name is occupied by an unowned volume",
			)
		}
		if err = e.initializeManagedVolume(
			ctx,
			request.Image,
			storage.Source,
			request.WorkloadID,
			request.ProcessUserMode,
		); err != nil {
			if created {
				_, _ = e.client.VolumeRemove(
					context.WithoutCancel(ctx),
					storage.Source,
					client.VolumeRemoveOptions{Force: true},
				)
			}
			return err
		}
		if created {
			createdVolumes = append(createdVolumes, storage.Source)
		}
	}
	if len(createdVolumes) > 0 &&
		len(request.StorageInitializationCommand) > 0 {
		if err := e.initializeStorageContent(ctx, request); err != nil {
			for _, volumeName := range createdVolumes {
				_, _ = e.client.VolumeRemove(
					context.WithoutCancel(ctx),
					volumeName,
					client.VolumeRemoveOptions{Force: true},
				)
			}
			return err
		}
	}
	return nil
}

// initializeStorageContent runs a shell-free, first-create command from the
// immutable workload image with access only to the Profile's managed storage.
func (e *Engine) initializeStorageContent(
	ctx context.Context,
	request StartRequest,
) error {
	command := request.StorageInitializationCommand
	if len(command) == 0 {
		return nil
	}
	securityOptions, _, err := e.workloadSecurityOptions(ctx)
	if err != nil {
		return err
	}
	workloadUser := e.config.DefaultWorkloadUser
	if request.ProcessUserMode == "IMAGE_DEFAULT" {
		workloadUser = ""
	}
	mounts := make([]mount.Mount, 0, len(request.Storage))
	tmpfs := map[string]string{
		"/tmp": tmpfsOptions(e.config.DefaultTmpfsSizeBytes, false),
	}
	for _, storage := range request.Storage {
		if storage.Type == "TMPFS" || storage.Type == "EPHEMERAL" {
			tmpfs[storage.TargetPath] = tmpfsOptions(
				storage.SizeBytes,
				storage.ReadOnly,
			)
			continue
		}
		mounts = append(mounts, mount.Mount{
			Type:     mount.TypeVolume,
			Source:   storage.Source,
			Target:   storage.TargetPath,
			ReadOnly: storage.ReadOnly,
			VolumeOptions: &mount.VolumeOptions{
				NoCopy: true,
			},
		})
	}
	initProcess := true
	pidsLimit := int64(32)
	created, err := e.client.ContainerCreate(ctx, client.ContainerCreateOptions{
		Config: &container.Config{
			Image:      request.Image,
			User:       workloadUser,
			Entrypoint: []string{command[0]},
			Cmd:        append([]string(nil), command[1:]...),
			WorkingDir: request.WorkingDirectory,
			Labels: map[string]string{
				labelStorageInit: "true",
				labelNodeID:      e.config.NodeID,
				labelWorkloadID:  request.WorkloadID,
			},
		},
		HostConfig: &container.HostConfig{
			AutoRemove:     false,
			CapDrop:        []string{"ALL"},
			Init:           &initProcess,
			NetworkMode:    container.NetworkMode(networkNone),
			Privileged:     false,
			ReadonlyRootfs: true,
			SecurityOpt:    securityOptions,
			Tmpfs:          tmpfs,
			Mounts:         mounts,
			Resources: container.Resources{
				Memory:    max(minimumMemoryBytes, request.MemoryBytes),
				NanoCPUs:  min(request.NanoCPUs, int64(500_000_000)),
				PidsLimit: &pidsLimit,
			},
		},
		Name: e.containerName(request.WorkloadID) + "-storage-init",
	})
	if err != nil {
		return fmt.Errorf("create managed storage content initializer: %w", err)
	}
	defer func() {
		_, _ = e.client.ContainerRemove(
			context.WithoutCancel(ctx),
			created.ID,
			client.ContainerRemoveOptions{Force: true},
		)
	}()
	if _, err = e.client.ContainerStart(
		ctx,
		created.ID,
		client.ContainerStartOptions{},
	); err != nil {
		return fmt.Errorf("start managed storage content initializer: %w", err)
	}
	waitContext, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	wait := e.client.ContainerWait(
		waitContext,
		created.ID,
		client.ContainerWaitOptions{
			Condition: container.WaitConditionNotRunning,
		},
	)
	select {
	case err = <-wait.Error:
		if err != nil {
			return fmt.Errorf(
				"wait for managed storage content initializer: %w",
				err,
			)
		}
	case result := <-wait.Result:
		if result.StatusCode != 0 {
			return fmt.Errorf(
				"managed storage content initializer exited with status %d",
				result.StatusCode,
			)
		}
	case <-waitContext.Done():
		return errors.New("managed storage content initializer timed out")
	}
	return nil
}

func (e *Engine) initializeManagedVolume(
	ctx context.Context,
	imageReference string,
	volumeName string,
	workloadID string,
	processUserMode string,
) error {
	if !e.config.LauncherEnabled {
		return errors.New("managed writable storage requires the Runtime launcher")
	}
	initProcess := true
	pidsLimit := int64(16)
	volumeSuffix := strings.TrimPrefix(
		volumeName, "open-simplepoint-managed-",
	)
	if len(volumeSuffix) > 20 {
		volumeSuffix = volumeSuffix[:20]
	}
	workloadSuffix := workloadID
	if len(workloadSuffix) > 32 {
		workloadSuffix = workloadSuffix[:32]
	}
	name := "open-simplepoint-storage-init-" + volumeSuffix + "-" +
		workloadSuffix
	volumeOwner := e.config.DefaultWorkloadUser
	if processUserMode == "IMAGE_DEFAULT" {
		image, inspectErr := e.client.ImageInspect(ctx, imageReference)
		if inspectErr != nil || image.Config == nil {
			return errors.New("inspect managed storage target image")
		}
		volumeOwner = strings.TrimSpace(image.Config.User)
		if volumeOwner == "" {
			volumeOwner = "0:0"
		}
	}
	created, err := e.client.ContainerCreate(ctx, client.ContainerCreateOptions{
		Config: &container.Config{
			Image: imageReference,
			User:  "0:0",
			Entrypoint: []string{
				e.config.LauncherMountTarget + "/launcher",
			},
			Cmd: []string{"initialize-managed-volume", volumeOwner},
			Labels: map[string]string{
				labelStorageInit: "true",
				labelNodeID:      e.config.NodeID,
			},
		},
		HostConfig: &container.HostConfig{
			AutoRemove:     false,
			CapDrop:        []string{"ALL"},
			CapAdd:         []string{"CHOWN", "FOWNER", "DAC_OVERRIDE"},
			Init:           &initProcess,
			NetworkMode:    container.NetworkMode(networkNone),
			ReadonlyRootfs: true,
			SecurityOpt:    []string{"no-new-privileges:true"},
			Tmpfs: map[string]string{
				"/tmp": tmpfsOptions(4*1024*1024, false),
			},
			Mounts: []mount.Mount{
				{
					Type:     mount.TypeVolume,
					Source:   e.config.SecretVolumeName,
					Target:   e.config.LauncherMountTarget,
					ReadOnly: true,
					VolumeOptions: &mount.VolumeOptions{
						NoCopy:  true,
						Subpath: launcherVolumeSubpath,
					},
				},
				{
					Type:          mount.TypeVolume,
					Source:        volumeName,
					Target:        "/managed-volume",
					ReadOnly:      false,
					VolumeOptions: &mount.VolumeOptions{NoCopy: true},
				},
			},
			Resources: container.Resources{
				Memory:    minimumMemoryBytes,
				NanoCPUs:  250_000_000,
				PidsLimit: &pidsLimit,
			},
		},
		Name: name,
	})
	if err != nil {
		return fmt.Errorf("create managed storage initializer: %w", err)
	}
	defer func() {
		_, _ = e.client.ContainerRemove(
			context.WithoutCancel(ctx),
			created.ID,
			client.ContainerRemoveOptions{Force: true},
		)
	}()
	if _, err = e.client.ContainerStart(
		ctx, created.ID, client.ContainerStartOptions{},
	); err != nil {
		return fmt.Errorf("start managed storage initializer: %w", err)
	}
	waitContext, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	wait := e.client.ContainerWait(
		waitContext,
		created.ID,
		client.ContainerWaitOptions{
			Condition: container.WaitConditionNotRunning,
		},
	)
	select {
	case err = <-wait.Error:
		if err != nil {
			return fmt.Errorf("wait for managed storage initializer: %w", err)
		}
	case result := <-wait.Result:
		if result.StatusCode != 0 {
			return fmt.Errorf(
				"managed storage initializer exited with status %d",
				result.StatusCode,
			)
		}
	case <-waitContext.Done():
		return errors.New("managed storage initializer timed out")
	}
	return nil
}

func routeNetworks(endpoints []string, routes map[string]string) []string {
	unique := make(map[string]struct{}, len(endpoints))
	result := make([]string, 0, len(endpoints))
	for _, endpoint := range endpoints {
		networkName := routes[endpoint]
		if _, exists := unique[networkName]; exists {
			continue
		}
		unique[networkName] = struct{}{}
		result = append(result, networkName)
	}
	sort.Strings(result)
	return result
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

func (e *Engine) launcherConfig(
	ctx context.Context,
	request StartRequest,
) (*workloadLauncherConfig, error) {
	secretEnvironment := make(map[string]string)
	for _, secret := range request.Secrets {
		if secret.TargetEnvironment == "" {
			continue
		}
		target, err := e.secretTarget(secret)
		if err != nil {
			return nil, err
		}
		secretEnvironment[secret.TargetEnvironment] = target
	}
	if len(secretEnvironment) == 0 {
		return nil, nil
	}
	if !e.config.LauncherEnabled {
		return nil, errors.New(
			"ENV_AT_EXEC requires the trusted Runtime launcher",
		)
	}
	inspect, err := e.client.ImageInspect(ctx, request.Image)
	if err != nil || inspect.Config == nil {
		return nil, errors.New("inspect Runtime launcher target image")
	}
	entrypoint := append([]string(nil), request.Entrypoint...)
	if len(entrypoint) == 0 {
		entrypoint = append(entrypoint, inspect.Config.Entrypoint...)
	}
	command := append([]string(nil), request.Command...)
	if len(command) == 0 {
		if len(request.Arguments) == 0 {
			command = append(command, inspect.Config.Cmd...)
		} else {
			command = append(command, request.Arguments...)
		}
	} else {
		command = append(command, request.Arguments...)
	}
	target := append(entrypoint, command...)
	if len(target) == 0 {
		return nil, errors.New("Runtime launcher target command is empty")
	}
	return &workloadLauncherConfig{
		Command:           target,
		SecretEnvironment: secretEnvironment,
	}, nil
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
	if len(request.Entrypoint)+len(request.Command)+len(request.Arguments)+
		len(request.StorageInitializationCommand) >
		maximumCommandItems {
		return StartRequest{}, errors.New("workload command contains too many arguments")
	}
	for _, token := range append(
		append(append([]string{}, request.Entrypoint...), request.Command...),
		append(request.Arguments, request.StorageInitializationCommand...)...,
	) {
		if strings.TrimSpace(token) == "" || strings.ContainsRune(token, '\x00') {
			return StartRequest{}, errors.New("workload process argument is invalid")
		}
	}
	request.Transport = strings.ToLower(strings.TrimSpace(request.Transport))
	if request.Transport != "" && request.Transport != "stdio" &&
		request.Transport != "streamable-http" {
		return StartRequest{}, errors.New("workload MCP transport is invalid")
	}
	if request.Transport == "streamable-http" {
		if request.ContainerPort < 1 || request.ContainerPort > 65535 ||
			!validTransportPath(request.TransportPath) ||
			e.config.TransportNetworkName == "" {
			return StartRequest{}, errors.New(
				"streamable HTTP container endpoint is invalid",
			)
		}
	} else if request.ContainerPort != 0 || request.TransportPath != "" {
		return StartRequest{}, errors.New(
			"stdio workload cannot declare an HTTP container endpoint",
		)
	}
	request.SandboxProfile = strings.ToUpper(strings.TrimSpace(
		request.SandboxProfile,
	))
	if request.SandboxProfile == "" {
		request.SandboxProfile = "STRICT"
	}
	if request.SandboxProfile != "STRICT" &&
		request.SandboxProfile != "DATA" &&
		request.SandboxProfile != "BROWSER" &&
		request.SandboxProfile != "WORKSPACE" &&
		request.SandboxProfile != "LEGACY" {
		return StartRequest{}, errors.New("workload sandbox profile is invalid")
	}
	request.ProcessUserMode = strings.ToUpper(strings.TrimSpace(
		request.ProcessUserMode,
	))
	if request.ProcessUserMode == "" {
		request.ProcessUserMode = "RUNTIME_DEFAULT"
	}
	if request.ProcessUserMode != "RUNTIME_DEFAULT" &&
		request.ProcessUserMode != "IMAGE_DEFAULT" {
		return StartRequest{}, errors.New("workload process user mode is invalid")
	}
	if request.WorkingDirectory != "" &&
		(!filepath.IsAbs(request.WorkingDirectory) ||
			filepath.Clean(request.WorkingDirectory) != request.WorkingDirectory) {
		return StartRequest{}, errors.New("workload working directory is invalid")
	}
	request.SessionMode = strings.ToUpper(strings.TrimSpace(request.SessionMode))
	if request.SessionMode == "" {
		request.SessionMode = "DEDICATED"
	}
	if request.SessionMode != "DEDICATED" &&
		request.SessionMode != "POOL_AFFINE" &&
		request.SessionMode != "SHARED_STATELESS" {
		return StartRequest{}, errors.New("workload MCP session mode is invalid")
	}
	if request.MaxSessions == 0 {
		request.MaxSessions = 1
	}
	if request.MaxSessions < 1 || request.MaxSessions > 256 ||
		(request.SessionMode == "DEDICATED" && request.MaxSessions != 1) {
		return StartRequest{}, errors.New("workload MCP session limit is invalid")
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
	if len(request.Storage) > 16 {
		return StartRequest{}, errors.New("workload contains too many storage mounts")
	}
	storageTargets := make([]string, 0, len(request.Storage))
	hasBrowserShm := false
	hasWorkspace := false
	for index := range request.Storage {
		storage := &request.Storage[index]
		storage.Type = strings.ToUpper(strings.TrimSpace(storage.Type))
		if storage.Type != "TMPFS" && storage.Type != "EPHEMERAL" &&
			storage.Type != "WORKSPACE_RO" && storage.Type != "WORKSPACE_RW" &&
			storage.Type != "PERSISTENT_VOLUME" &&
			storage.Type != "OBJECT_SNAPSHOT" {
			return StartRequest{}, errors.New("workload storage type is invalid")
		}
		if !validStorageTarget(storage.TargetPath) {
			return StartRequest{}, errors.New("workload storage target is invalid")
		}
		for _, target := range storageTargets {
			if storage.TargetPath == target ||
				strings.HasPrefix(storage.TargetPath, target+"/") ||
				strings.HasPrefix(target, storage.TargetPath+"/") {
				return StartRequest{}, errors.New(
					"workload storage targets overlap",
				)
			}
		}
		storageTargets = append(storageTargets, storage.TargetPath)
		if storage.Type == "WORKSPACE_RO" || storage.Type == "WORKSPACE_RW" {
			hasWorkspace = true
		}
		if storage.Type == "TMPFS" || storage.Type == "EPHEMERAL" {
			if storage.Source != "" {
				return StartRequest{}, errors.New(
					"ephemeral workload storage cannot have a source",
				)
			}
			if storage.SizeBytes == 0 {
				storage.SizeBytes = e.config.DefaultTmpfsSizeBytes
			}
			if storage.SizeBytes < 1024*1024 ||
				storage.SizeBytes > e.config.MaxMemoryBytes {
				return StartRequest{}, errors.New(
					"workload ephemeral storage size is invalid",
				)
			}
			if storage.TargetPath == "/dev/shm" && storage.Type == "TMPFS" {
				hasBrowserShm = storage.SizeBytes <= 512*1024*1024
			}
			continue
		}
		if !managedVolumePattern.MatchString(storage.Source) ||
			storage.SizeBytes != 0 {
			return StartRequest{}, errors.New(
				"managed workload storage source is invalid",
			)
		}
		if (storage.Type == "WORKSPACE_RO" ||
			storage.Type == "OBJECT_SNAPSHOT") && !storage.ReadOnly {
			return StartRequest{}, errors.New(
				"read-only workload storage cannot be writable",
			)
		}
	}
	if request.SandboxProfile == "BROWSER" && !hasBrowserShm {
		return StartRequest{}, errors.New(
			"browser sandbox requires a bounded /dev/shm tmpfs",
		)
	}
	if request.SandboxProfile == "WORKSPACE" && !hasWorkspace {
		return StartRequest{}, errors.New(
			"workspace sandbox requires a managed workspace mount",
		)
	}
	if request.SandboxProfile == "DATA" &&
		request.NetworkMode != networkTCPEgress &&
		request.NetworkMode != networkInternal {
		return StartRequest{}, errors.New(
			"data sandbox requires exact TCP or internal-service networking",
		)
	}
	if len(request.Secrets) > e.config.MaxSecretFiles {
		return StartRequest{}, errors.New("workload contains too many secret files")
	}
	secretNames := make(map[string]struct{}, len(request.Secrets))
	secretTargets := make(map[string]struct{}, len(request.Secrets))
	secretEnvironments := make(map[string]struct{}, len(request.Secrets))
	var secretTotalBytes int64
	for _, secret := range request.Secrets {
		if !workloadIDPattern.MatchString(secret.Name) {
			return StartRequest{}, errors.New("workload secret file name is invalid")
		}
		if _, exists := secretNames[secret.Name]; exists {
			return StartRequest{}, errors.New("workload secret file names must be unique")
		}
		secretNames[secret.Name] = struct{}{}
		target, targetErr := e.secretTarget(secret)
		if targetErr != nil {
			return StartRequest{}, targetErr
		}
		if _, exists := secretTargets[target]; exists {
			return StartRequest{}, errors.New(
				"workload secret targets must be unique",
			)
		}
		secretTargets[target] = struct{}{}
		if secret.TargetEnvironment != "" {
			if !environmentPattern.MatchString(secret.TargetEnvironment) {
				return StartRequest{}, errors.New(
					"workload secret environment target is invalid",
				)
			}
			if _, exists := secretEnvironments[secret.TargetEnvironment]; exists {
				return StartRequest{}, errors.New(
					"workload secret environment targets must be unique",
				)
			}
			secretEnvironments[secret.TargetEnvironment] = struct{}{}
		}
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
		!(request.NetworkMode == networkEgress && e.config.EgressEnabled) &&
		!(request.NetworkMode == networkTCPEgress &&
			len(e.config.TCPEgressRoutes) > 0) &&
		!(request.NetworkMode == networkInternal &&
			len(e.config.InternalServiceRoutes) > 0) {
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
	} else if request.NetworkMode == networkTCPEgress ||
		request.NetworkMode == networkInternal {
		routes := e.config.TCPEgressRoutes
		if request.NetworkMode == networkInternal {
			routes = e.config.InternalServiceRoutes
		}
		normalized, err := normalizeNetworkEndpoints(
			request.EgressAllowlist,
			routes,
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

func normalizeNetworkEndpoints(
	values []string,
	routes map[string]string,
	maximum int,
) ([]string, error) {
	if len(values) == 0 || len(values) > maximum {
		return nil, errors.New("workload network endpoint count is invalid")
	}
	unique := make(map[string]struct{}, len(values))
	result := make([]string, 0, len(values))
	for _, value := range values {
		endpoint := strings.ToLower(strings.TrimSpace(value))
		separator := strings.LastIndexByte(endpoint, ':')
		if separator < 1 || separator == len(endpoint)-1 ||
			strings.HasPrefix(endpoint, "*.") {
			return nil, errors.New("workload network endpoint is invalid")
		}
		port, err := strconv.Atoi(endpoint[separator+1:])
		_, allowed := routes[endpoint]
		_, duplicate := unique[endpoint]
		if err != nil || port < 1 || port > 65535 || !allowed || duplicate {
			return nil, errors.New(
				"workload network endpoint is not approved or is duplicated",
			)
		}
		unique[endpoint] = struct{}{}
		result = append(result, endpoint)
	}
	sort.Strings(result)
	return result, nil
}

func (e *Engine) prepareSecrets(
	request StartRequest,
	launcherConfig *workloadLauncherConfig,
) (string, error) {
	if len(request.Secrets) == 0 && launcherConfig == nil {
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
		target, targetErr := e.secretTarget(secret)
		if targetErr != nil {
			return "", targetErr
		}
		relative, _ := filepath.Rel(e.config.SecretMountTarget, target)
		path := filepath.Join(directory, relative)
		if err = os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return "", fmt.Errorf("create workload secret target: %w", err)
		}
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
	if launcherConfig != nil {
		value, marshalErr := json.Marshal(launcherConfig)
		if marshalErr != nil {
			return "", fmt.Errorf("encode Runtime launcher config: %w", marshalErr)
		}
		path := filepath.Join(directory, ".runtime-launcher.json")
		if err = os.WriteFile(path, value, 0o400); err != nil {
			return "", fmt.Errorf("write Runtime launcher config: %w", err)
		}
	}
	return subpath, nil
}

func (e *Engine) secretTarget(secret SecretFile) (string, error) {
	target := strings.TrimSpace(secret.TargetPath)
	if target == "" {
		target = filepath.Join(e.config.SecretMountTarget, secret.Name)
	}
	if !filepath.IsAbs(target) || filepath.Clean(target) != target {
		return "", errors.New("workload secret target path is invalid")
	}
	relative, err := filepath.Rel(e.config.SecretMountTarget, target)
	if err != nil || relative == "." || relative == ".." ||
		strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", errors.New(
			"workload secret target must remain below the secret mount",
		)
	}
	return target, nil
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
	if e.config.RequireDigest && !digestPattern.MatchString(image) {
		return errors.New("OCI image reference must be pinned by sha256 digest")
	}
	return e.validateResolvableImageReference(image)
}

func (e *Engine) validateResolvableImageReference(image string) error {
	image = strings.TrimSpace(image)
	if image == "" || len(image) > 512 {
		return errors.New("OCI image reference is invalid")
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
	key = strings.ToUpper(key)
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

func validStorageTarget(target string) bool {
	if !filepath.IsAbs(target) || filepath.Clean(target) != target ||
		target == "/" || target == "/tmp" {
		return false
	}
	return target != "/run/secrets/simplepoint" &&
		!strings.HasPrefix(target, "/run/secrets/simplepoint/") &&
		target != "/run/simplepoint-runtime" &&
		!strings.HasPrefix(target, "/run/simplepoint-runtime/")
}

func validTransportPath(value string) bool {
	if !strings.HasPrefix(value, "/") {
		return false
	}
	if strings.HasPrefix(value, "//") || strings.Contains(value, "..") ||
		strings.ContainsAny(value, "?#%\\\x00") {
		return false
	}
	for _, character := range value {
		if character <= 0x20 || character == 0x7f {
			return false
		}
	}
	return true
}

func tmpfsOptions(sizeBytes int64, readOnly bool) string {
	mode := "rw"
	if readOnly {
		mode = "ro"
	}
	return fmt.Sprintf(
		"%s,noexec,nosuid,nodev,size=%d",
		mode,
		sizeBytes,
	)
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
