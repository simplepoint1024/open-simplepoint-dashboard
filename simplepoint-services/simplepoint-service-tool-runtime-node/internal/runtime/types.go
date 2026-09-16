package runtime

import "time"

const (
	labelManaged         = "io.simplepoint.tool-runtime.managed"
	labelNodeID          = "io.simplepoint.tool-runtime.node-id"
	labelWorkloadID      = "io.simplepoint.tool-runtime.workload-id"
	labelExecutionID     = "io.simplepoint.tool-runtime.execution-id"
	labelTenantID        = "io.simplepoint.tool-runtime.tenant-id"
	labelLeaseID         = "io.simplepoint.tool-runtime.lease-id"
	labelFencingToken    = "io.simplepoint.tool-runtime.fencing-token"
	labelDeadline        = "io.simplepoint.tool-runtime.deadline"
	labelMCPTransport    = "io.simplepoint.mcp.transport"
	labelMCPProtocol     = "io.simplepoint.mcp.protocol-version"
	labelMCPSessionMode  = "io.simplepoint.mcp.session-mode"
	labelMCPMaxSessions  = "io.simplepoint.mcp.max-sessions"
	labelMCPPort         = "io.simplepoint.mcp.container-port"
	labelMCPPath         = "io.simplepoint.mcp.transport-path"
	labelSandboxProfile  = "io.simplepoint.runtime.sandbox-profile"
	labelManagedStorage  = "io.simplepoint.tool-runtime.managed-storage"
	labelStorageInit     = "io.simplepoint.tool-runtime.storage-initializer"
	labelOCIImageTitle   = "org.opencontainers.image.title"
	networkNone          = "none"
	networkBridge        = "bridge"
	networkEgress        = "egress"
	networkTCPEgress     = "tcp-egress"
	networkInternal      = "internal-service"
	defaultStopTimeout   = 10
	minimumMemoryBytes   = 32 * 1024 * 1024
	maximumCommandItems  = 128
	maximumEnvironment   = 128
	maximumEnvironmentKV = 4096
	secretDirectoryEnv   = "SIMPLEPOINT_SECRET_DIR"
)

// SecretFile is one short-lived file delivered by the trusted control plane.
type SecretFile struct {
	Name              string `json:"name"`
	Value             string `json:"value"`
	TargetPath        string `json:"targetPath,omitempty"`
	TargetEnvironment string `json:"targetEnvironment,omitempty"`
}

// StorageMount is one platform-resolved, non-host-bind workload mount.
type StorageMount struct {
	Type       string `json:"type"`
	Source     string `json:"source,omitempty"`
	TargetPath string `json:"targetPath"`
	ReadOnly   bool   `json:"readOnly,omitempty"`
	SizeBytes  int64  `json:"sizeBytes,omitempty"`
}

// StartRequest is the immutable workload envelope accepted from the scheduler.
type StartRequest struct {
	WorkloadID                   string            `json:"workloadId"`
	LeaseID                      string            `json:"leaseId"`
	FencingToken                 int64             `json:"fencingToken"`
	ExecutionID                  string            `json:"executionId"`
	TenantID                     string            `json:"tenantId"`
	Image                        string            `json:"image"`
	Transport                    string            `json:"transport,omitempty"`
	Entrypoint                   []string          `json:"entrypoint,omitempty"`
	Command                      []string          `json:"command,omitempty"`
	Arguments                    []string          `json:"arguments,omitempty"`
	WorkingDirectory             string            `json:"workingDirectory,omitempty"`
	Environment                  map[string]string `json:"environment,omitempty"`
	SessionMode                  string            `json:"sessionMode,omitempty"`
	MaxSessions                  int               `json:"maxSessions,omitempty"`
	MemoryBytes                  int64             `json:"memoryBytes,omitempty"`
	NanoCPUs                     int64             `json:"nanoCpus,omitempty"`
	PidsLimit                    int64             `json:"pidsLimit,omitempty"`
	TimeoutSeconds               int64             `json:"timeoutSeconds,omitempty"`
	NetworkMode                  string            `json:"networkMode,omitempty"`
	Secrets                      []SecretFile      `json:"secrets,omitempty"`
	EgressAllowlist              []string          `json:"egressAllowlist,omitempty"`
	Storage                      []StorageMount    `json:"storage,omitempty"`
	ContainerPort                int               `json:"containerPort,omitempty"`
	TransportPath                string            `json:"transportPath,omitempty"`
	SandboxProfile               string            `json:"sandboxProfile,omitempty"`
	ProcessUserMode              string            `json:"processUserMode,omitempty"`
	StorageInitializationCommand []string          `json:"storageInitializationCommand,omitempty"`
}

// ImageStatus describes the content-addressed image selected for execution.
type ImageStatus struct {
	Reference           string            `json:"reference"`
	ImageID             string            `json:"imageId"`
	RepoDigests         []string          `json:"repoDigests"`
	Labels              map[string]string `json:"labels"`
	MCPTransport        string            `json:"mcpTransport"`
	ProtocolVersion     string            `json:"protocolVersion"`
	SupplyChainAdmitted bool              `json:"supplyChainAdmitted"`
	SignatureVerified   bool              `json:"signatureVerified"`
	SBOMVerified        bool              `json:"sbomVerified"`
	AdmissionPolicyHash string            `json:"admissionPolicyHash,omitempty"`
}

// WorkloadStatus is a sanitized view of one runtime-owned container.
type WorkloadStatus struct {
	WorkloadID   string    `json:"workloadId"`
	LeaseID      string    `json:"leaseId"`
	FencingToken int64     `json:"fencingToken"`
	ExecutionID  string    `json:"executionId,omitempty"`
	TenantID     string    `json:"tenantId,omitempty"`
	ContainerID  string    `json:"containerId"`
	Image        string    `json:"image"`
	State        string    `json:"state"`
	Health       string    `json:"health,omitempty"`
	StartedAt    string    `json:"startedAt,omitempty"`
	FinishedAt   string    `json:"finishedAt,omitempty"`
	ExitCode     int       `json:"exitCode,omitempty"`
	Deadline     time.Time `json:"deadline,omitempty"`
	RuntimeNode  string    `json:"runtimeNode"`
}

// NodeStatus describes Docker connectivity and fixed sandbox limits.
type NodeStatus struct {
	NodeID                      string    `json:"nodeId"`
	DisplayName                 string    `json:"displayName"`
	Status                      string    `json:"status"`
	EngineAPIVersion            string    `json:"engineApiVersion"`
	EngineOSType                string    `json:"engineOsType"`
	CPUCores                    int       `json:"cpuCores"`
	MemoryBytes                 int64     `json:"memoryBytes"`
	MaxWorkloads                int       `json:"maxWorkloads"`
	RunningWorkloads            int       `json:"runningWorkloads"`
	CachedImageDigests          []string  `json:"cachedImageDigests"`
	MaxWorkloadMemoryBytes      int64     `json:"maxWorkloadMemoryBytes"`
	MaxWorkloadNanoCPUs         int64     `json:"maxWorkloadNanoCpus"`
	MaxWorkloadPidsLimit        int64     `json:"maxWorkloadPidsLimit"`
	RequireImageDigest          bool      `json:"requireImageDigest"`
	RequireMCPLabels            bool      `json:"requireMcpLabels"`
	AllowBridgeNetwork          bool      `json:"allowBridgeNetwork"`
	AllowEgressNetwork          bool      `json:"allowEgressNetwork"`
	RequireSupplyChainAdmission bool      `json:"requireSupplyChainAdmission"`
	SeccompEnforced             bool      `json:"seccompEnforced"`
	SeccompProfileHash          string    `json:"seccompProfileHash,omitempty"`
	AppArmorEnforced            bool      `json:"appArmorEnforced"`
	AppArmorProfile             string    `json:"appArmorProfile,omitempty"`
	CheckedAt                   time.Time `json:"checkedAt"`
}
