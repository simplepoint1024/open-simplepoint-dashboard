package controlplane

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	runtimeapi "github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/runtime"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/tlsidentity"
)

const (
	controlPlaneHeader       = "X-SimplePoint-Tool-Runtime-Control-Token"
	defaultHeartbeatInterval = 10 * time.Second
	maximumResponseBytes     = 64 * 1024
)

// StatusProvider supplies Docker Engine health and capacity to the control plane.
type StatusProvider interface {
	NodeStatus(context.Context) (runtimeapi.NodeStatus, error)
}

// Client maintains this runtime process's registration and heartbeat lease.
type Client struct {
	config         config.Config
	instanceID     string
	runtimeVersion string
	statusProvider StatusProvider
	logger         *slog.Logger
	httpClient     *http.Client
}

type registrationRequest struct {
	InstanceID                  string            `json:"instanceId"`
	DisplayName                 string            `json:"displayName"`
	AdvertiseURL                string            `json:"advertiseUrl"`
	RuntimeVersion              string            `json:"runtimeVersion"`
	EngineAPIVersion            string            `json:"engineApiVersion"`
	EngineOSType                string            `json:"engineOsType"`
	CPUCores                    int               `json:"cpuCores"`
	MemoryBytes                 int64             `json:"memoryBytes"`
	MaxWorkloads                int               `json:"maxWorkloads"`
	RunningWorkloads            int               `json:"runningWorkloads"`
	CachedImageDigests          []string          `json:"cachedImageDigests"`
	MaxWorkloadMemoryBytes      int64             `json:"maxWorkloadMemoryBytes"`
	MaxWorkloadNanoCPUs         int64             `json:"maxWorkloadNanoCpus"`
	MaxWorkloadPidsLimit        int64             `json:"maxWorkloadPidsLimit"`
	RequireImageDigest          bool              `json:"requireImageDigest"`
	RequireMCPLabels            bool              `json:"requireMcpLabels"`
	AllowBridgeNetwork          bool              `json:"allowBridgeNetwork"`
	AllowEgressNetwork          bool              `json:"allowEgressNetwork"`
	RequireSupplyChainAdmission bool              `json:"requireSupplyChainAdmission"`
	SeccompEnforced             bool              `json:"seccompEnforced"`
	SeccompProfileHash          string            `json:"seccompProfileHash"`
	AppArmorEnforced            bool              `json:"appArmorEnforced"`
	AppArmorProfile             string            `json:"appArmorProfile"`
	Labels                      map[string]string `json:"labels"`
}

type heartbeatRequest struct {
	InstanceID             string   `json:"instanceId"`
	EngineAPIVersion       string   `json:"engineApiVersion"`
	EngineOSType           string   `json:"engineOsType"`
	CPUCores               int      `json:"cpuCores"`
	MemoryBytes            int64    `json:"memoryBytes"`
	MaxWorkloads           int      `json:"maxWorkloads"`
	RunningWorkloads       int      `json:"runningWorkloads"`
	MaxWorkloadMemoryBytes int64    `json:"maxWorkloadMemoryBytes"`
	MaxWorkloadNanoCPUs    int64    `json:"maxWorkloadNanoCpus"`
	MaxWorkloadPidsLimit   int64    `json:"maxWorkloadPidsLimit"`
	CachedImageDigests     []string `json:"cachedImageDigests"`
	Healthy                bool     `json:"healthy"`
	Error                  string   `json:"error,omitempty"`
}

type offlineRequest struct {
	InstanceID string `json:"instanceId"`
}

type controlResponse struct {
	NodeID                   string    `json:"nodeId"`
	InstanceID               string    `json:"instanceId"`
	Generation               int64     `json:"generation"`
	Status                   string    `json:"status"`
	AcceptedAt               time.Time `json:"acceptedAt"`
	HeartbeatIntervalSeconds int64     `json:"heartbeatIntervalSeconds"`
	HeartbeatTimeoutSeconds  int64     `json:"heartbeatTimeoutSeconds"`
}

type responseError struct {
	StatusCode int
}

func (e responseError) Error() string {
	return fmt.Sprintf("control plane returned HTTP %d", e.StatusCode)
}

// New creates a control-plane client with a unique process generation.
func New(
	cfg config.Config,
	runtimeVersion string,
	statusProvider StatusProvider,
	logger *slog.Logger,
) (*Client, error) {
	instanceID, err := newInstanceID()
	if err != nil {
		return nil, fmt.Errorf("create runtime process instance ID: %w", err)
	}
	httpClient := &http.Client{
		Timeout: cfg.RequestTimeout,
		CheckRedirect: func(
			_ *http.Request,
			_ []*http.Request,
		) error {
			return http.ErrUseLastResponse
		},
	}
	if cfg.MTLSEnabled {
		tlsConfig, tlsErr := tlsidentity.ClientConfig(
			cfg.TLSCertificateFile,
			cfg.TLSPrivateKeyFile,
			cfg.TLSCAFile,
			cfg.TLSControlServerName,
			cfg.NodeTLSIdentity(),
		)
		if tlsErr != nil {
			return nil, fmt.Errorf("configure control-plane mTLS: %w", tlsErr)
		}
		httpClient.Transport = &http.Transport{TLSClientConfig: tlsConfig}
	}
	return &Client{
		config:         cfg,
		instanceID:     instanceID,
		runtimeVersion: runtimeVersion,
		statusProvider: statusProvider,
		logger:         logger,
		httpClient:     httpClient,
	}, nil
}

// Run registers the process, renews its lease, and reports graceful shutdown.
func (c *Client) Run(ctx context.Context) {
	if c.config.ControlPlaneURL == "" {
		return
	}
	registered := false
	defer func() {
		if registered {
			c.reportOffline()
		}
	}()

	var lastStatus runtimeapi.NodeStatus
	for {
		status, response, err := c.register(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			c.logger.Warn(
				"register runtime node with control plane",
				"error", err,
				"nodeId", c.config.NodeID,
			)
			if !wait(ctx, c.config.RegistrationRetry) {
				return
			}
			continue
		}

		registered = true
		lastStatus = status
		c.logger.Info(
			"runtime node registered with control plane",
			"nodeId", response.NodeID,
			"generation", response.Generation,
		)
		reRegister, nextStatus := c.heartbeatLoop(ctx, response, lastStatus)
		lastStatus = nextStatus
		if !reRegister {
			return
		}
	}
}

func (c *Client) register(
	ctx context.Context,
) (runtimeapi.NodeStatus, controlResponse, error) {
	status, err := c.statusProvider.NodeStatus(ctx)
	if err != nil {
		return runtimeapi.NodeStatus{}, controlResponse{}, err
	}
	displayName := strings.TrimSpace(status.DisplayName)
	if displayName == "" {
		displayName = c.config.NodeID
	}
	request := registrationRequest{
		InstanceID:                  c.instanceID,
		DisplayName:                 displayName,
		AdvertiseURL:                c.config.AdvertiseURL,
		RuntimeVersion:              c.runtimeVersion,
		EngineAPIVersion:            status.EngineAPIVersion,
		EngineOSType:                status.EngineOSType,
		CPUCores:                    status.CPUCores,
		MemoryBytes:                 status.MemoryBytes,
		MaxWorkloads:                c.config.MaxWorkloads,
		RunningWorkloads:            status.RunningWorkloads,
		CachedImageDigests:          status.CachedImageDigests,
		MaxWorkloadMemoryBytes:      status.MaxWorkloadMemoryBytes,
		MaxWorkloadNanoCPUs:         status.MaxWorkloadNanoCPUs,
		MaxWorkloadPidsLimit:        status.MaxWorkloadPidsLimit,
		RequireImageDigest:          c.config.RequireDigest,
		RequireMCPLabels:            c.config.RequireMCPLabels,
		AllowBridgeNetwork:          c.config.AllowBridgeNetwork,
		AllowEgressNetwork:          c.config.EgressEnabled,
		RequireSupplyChainAdmission: c.config.SupplyChainEnabled,
		SeccompEnforced:             status.SeccompEnforced,
		SeccompProfileHash:          status.SeccompProfileHash,
		AppArmorEnforced:            status.AppArmorEnforced,
		AppArmorProfile:             status.AppArmorProfile,
		Labels:                      c.config.NodeLabels,
	}
	var response controlResponse
	err = c.post(
		ctx,
		"registration",
		request,
		&response,
	)
	if err != nil {
		return runtimeapi.NodeStatus{}, controlResponse{}, err
	}
	if err = c.validateResponse(response); err != nil {
		return runtimeapi.NodeStatus{}, controlResponse{}, err
	}
	return status, response, nil
}

func (c *Client) heartbeatLoop(
	ctx context.Context,
	response controlResponse,
	lastStatus runtimeapi.NodeStatus,
) (bool, runtimeapi.NodeStatus) {
	interval := heartbeatInterval(response.HeartbeatIntervalSeconds)
	for wait(ctx, interval) {
		currentStatus, statusErr := c.statusProvider.NodeStatus(ctx)
		heartbeat := heartbeatRequest{
			InstanceID:             c.instanceID,
			EngineAPIVersion:       lastStatus.EngineAPIVersion,
			EngineOSType:           lastStatus.EngineOSType,
			CPUCores:               lastStatus.CPUCores,
			MemoryBytes:            lastStatus.MemoryBytes,
			MaxWorkloads:           c.config.MaxWorkloads,
			RunningWorkloads:       lastStatus.RunningWorkloads,
			MaxWorkloadMemoryBytes: lastStatus.MaxWorkloadMemoryBytes,
			MaxWorkloadNanoCPUs:    lastStatus.MaxWorkloadNanoCPUs,
			MaxWorkloadPidsLimit:   lastStatus.MaxWorkloadPidsLimit,
			CachedImageDigests:     lastStatus.CachedImageDigests,
			Healthy:                statusErr == nil,
		}
		if statusErr == nil {
			lastStatus = currentStatus
			heartbeat.EngineAPIVersion = currentStatus.EngineAPIVersion
			heartbeat.EngineOSType = currentStatus.EngineOSType
			heartbeat.CPUCores = currentStatus.CPUCores
			heartbeat.MemoryBytes = currentStatus.MemoryBytes
			heartbeat.RunningWorkloads = currentStatus.RunningWorkloads
			heartbeat.MaxWorkloadMemoryBytes = currentStatus.MaxWorkloadMemoryBytes
			heartbeat.MaxWorkloadNanoCPUs = currentStatus.MaxWorkloadNanoCPUs
			heartbeat.MaxWorkloadPidsLimit = currentStatus.MaxWorkloadPidsLimit
			heartbeat.CachedImageDigests = currentStatus.CachedImageDigests
		} else {
			heartbeat.Error = "Docker Engine unavailable"
		}

		var next controlResponse
		err := c.post(ctx, "heartbeat", heartbeat, &next)
		if err != nil {
			if ctx.Err() != nil {
				return false, lastStatus
			}
			var responseErr responseError
			if errors.As(err, &responseErr) &&
				(responseErr.StatusCode == http.StatusNotFound ||
					responseErr.StatusCode == http.StatusConflict) {
				c.logger.Warn(
					"runtime node registration was fenced; registering again",
					"nodeId", c.config.NodeID,
					"status", responseErr.StatusCode,
				)
				return true, lastStatus
			}
			c.logger.Warn(
				"renew runtime node heartbeat",
				"error", err,
				"nodeId", c.config.NodeID,
			)
			continue
		}
		if err = c.validateResponse(next); err != nil {
			c.logger.Warn(
				"validate runtime node heartbeat response",
				"error", err,
				"nodeId", c.config.NodeID,
			)
			return true, lastStatus
		}
		interval = heartbeatInterval(next.HeartbeatIntervalSeconds)
	}
	return false, lastStatus
}

func (c *Client) reportOffline() {
	timeout := c.config.ShutdownTimeout
	if timeout > 5*time.Second {
		timeout = 5 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var response controlResponse
	if err := c.post(ctx, "offline", offlineRequest{
		InstanceID: c.instanceID,
	}, &response); err != nil {
		c.logger.Warn(
			"report runtime node shutdown",
			"error", err,
			"nodeId", c.config.NodeID,
		)
	}
}

func (c *Client) post(
	ctx context.Context,
	operation string,
	payload any,
	target any,
) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("encode control-plane request: %w", err)
	}
	endpoint := c.config.ControlPlaneURL +
		"/internal/runtime/nodes/" +
		url.PathEscape(c.config.NodeID) +
		"/" + operation
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		endpoint,
		bytes.NewReader(body),
	)
	if err != nil {
		return fmt.Errorf("create control-plane request: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	if !c.config.MTLSEnabled {
		request.Header.Set(controlPlaneHeader, c.config.ControlPlaneToken)
	}
	request.Header.Set("User-Agent", "open-simplepoint-tool-runtime/"+c.runtimeVersion)

	response, err := c.httpClient.Do(request)
	if err != nil {
		return fmt.Errorf("call control plane: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK ||
		response.StatusCode >= http.StatusMultipleChoices {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maximumResponseBytes))
		return responseError{StatusCode: response.StatusCode}
	}
	if err = json.NewDecoder(
		io.LimitReader(response.Body, maximumResponseBytes),
	).Decode(target); err != nil {
		return fmt.Errorf("decode control-plane response: %w", err)
	}
	return nil
}

func (c *Client) validateResponse(response controlResponse) error {
	if response.NodeID != c.config.NodeID ||
		response.InstanceID != c.instanceID ||
		response.Generation <= 0 {
		return errors.New("control-plane response identity is invalid")
	}
	return nil
}

func heartbeatInterval(seconds int64) time.Duration {
	if seconds <= 0 || seconds > 300 {
		return defaultHeartbeatInterval
	}
	return time.Duration(seconds) * time.Second
}

func wait(ctx context.Context, duration time.Duration) bool {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func newInstanceID() (string, error) {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return hex.EncodeToString(value), nil
}
