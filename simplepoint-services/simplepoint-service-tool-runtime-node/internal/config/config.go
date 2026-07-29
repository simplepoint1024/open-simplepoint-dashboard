package config

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	defaultAddress           = ":2891"
	defaultMemoryBytes       = int64(256 * 1024 * 1024)
	defaultMaxMemoryBytes    = int64(2 * 1024 * 1024 * 1024)
	defaultNanoCPUs          = int64(500_000_000)
	defaultMaxNanoCPUs       = int64(4_000_000_000)
	defaultPidsLimit         = int64(128)
	defaultMaxPidsLimit      = int64(512)
	defaultRequestTimeout    = 30 * time.Second
	defaultShutdownTimeout   = 10 * time.Second
	defaultRegistrationRetry = 5 * time.Second
	defaultReportedImages    = 256
)

// Config contains the trusted runtime-node boundary and sandbox limits.
type Config struct {
	Address                    string
	NodeID                     string
	InternalToken              string
	ControlPlaneURL            string
	ControlPlaneToken          string
	AdvertiseURL               string
	NodeLabels                 map[string]string
	MaxWorkloads               int
	MaxReportedImageDigests    int
	RegistrationRetry          time.Duration
	Namespace                  string
	AllowedRegistries          map[string]struct{}
	RequireDigest              bool
	RequireMCPLabels           bool
	DefaultMemoryBytes         int64
	MaxMemoryBytes             int64
	DefaultNanoCPUs            int64
	MaxNanoCPUs                int64
	DefaultPidsLimit           int64
	MaxPidsLimit               int64
	RequestTimeout             time.Duration
	ShutdownTimeout            time.Duration
	MaxRequestBytes            int64
	DefaultWorkloadUser        string
	DefaultTmpfsSizeBytes      int64
	AllowBridgeNetwork         bool
	EgressEnabled              bool
	EgressNetworkName          string
	EgressProxyURL             string
	EgressSigningKey           string
	MaxEgressHosts             int
	RequiredProtocolVersion    string
	MTLSEnabled                bool
	TLSCertificateFile         string
	TLSPrivateKeyFile          string
	TLSCAFile                  string
	TLSServerName              string
	TLSControlServerName       string
	TLSExpectedAIIdentity      string
	SecretRoot                 string
	SecretVolumeName           string
	SecretMountTarget          string
	MaxSecretFiles             int
	MaxSecretFileBytes         int64
	MaxSecretTotalBytes        int64
	SupplyChainEnabled         bool
	SupplyChainVerifierURL     string
	SupplyChainServerName      string
	SupplyChainIdentity        string
	SupplyChainTimeout         time.Duration
	SeccompProfileFile         string
	SeccompRequired            bool
	AppArmorProfile            string
	AppArmorEnabled            bool
	AppArmorRequired           bool
	TLSExpectedGatewayIdentity string
	MCPRequestTimeout          time.Duration
	MaxMCPMessageBytes         int64
}

// Load reads and validates runtime-node environment configuration.
func Load() (Config, error) {
	hostname, err := os.Hostname()
	if err != nil {
		return Config{}, fmt.Errorf("resolve hostname: %w", err)
	}
	nodeLabels, err := labelMap(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_NODE_LABELS"))
	if err != nil {
		return Config{}, err
	}
	cfg := Config{
		Address:           env("SIMPLEPOINT_TOOL_RUNTIME_ADDRESS", defaultAddress),
		NodeID:            env("SIMPLEPOINT_TOOL_RUNTIME_NODE_ID", hostname),
		InternalToken:     strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_INTERNAL_TOKEN")),
		ControlPlaneURL:   strings.TrimRight(strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_CONTROL_PLANE_URL")), "/"),
		ControlPlaneToken: strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_CONTROL_PLANE_TOKEN")),
		AdvertiseURL:      strings.TrimRight(strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_ADVERTISE_URL")), "/"),
		NodeLabels:        nodeLabels,
		MaxWorkloads:      int(envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_WORKLOADS", 64)),
		MaxReportedImageDigests: int(envInt64(
			"SIMPLEPOINT_TOOL_RUNTIME_MAX_REPORTED_IMAGE_DIGESTS",
			defaultReportedImages,
		)),
		RegistrationRetry:       envDuration("SIMPLEPOINT_TOOL_RUNTIME_REGISTRATION_RETRY", defaultRegistrationRetry),
		Namespace:               env("SIMPLEPOINT_TOOL_RUNTIME_NAMESPACE", "open-simplepoint-tool-"),
		AllowedRegistries:       csvSet(env("SIMPLEPOINT_TOOL_RUNTIME_ALLOWED_REGISTRIES", "docker.io")),
		RequireDigest:           envBool("SIMPLEPOINT_TOOL_RUNTIME_REQUIRE_DIGEST", true),
		RequireMCPLabels:        envBool("SIMPLEPOINT_TOOL_RUNTIME_REQUIRE_MCP_LABELS", true),
		DefaultMemoryBytes:      envInt64("SIMPLEPOINT_TOOL_RUNTIME_DEFAULT_MEMORY_BYTES", defaultMemoryBytes),
		MaxMemoryBytes:          envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_MEMORY_BYTES", defaultMaxMemoryBytes),
		DefaultNanoCPUs:         envInt64("SIMPLEPOINT_TOOL_RUNTIME_DEFAULT_NANO_CPUS", defaultNanoCPUs),
		MaxNanoCPUs:             envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_NANO_CPUS", defaultMaxNanoCPUs),
		DefaultPidsLimit:        envInt64("SIMPLEPOINT_TOOL_RUNTIME_DEFAULT_PIDS_LIMIT", defaultPidsLimit),
		MaxPidsLimit:            envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_PIDS_LIMIT", defaultMaxPidsLimit),
		RequestTimeout:          envDuration("SIMPLEPOINT_TOOL_RUNTIME_REQUEST_TIMEOUT", defaultRequestTimeout),
		ShutdownTimeout:         envDuration("SIMPLEPOINT_TOOL_RUNTIME_SHUTDOWN_TIMEOUT", defaultShutdownTimeout),
		MaxRequestBytes:         envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_REQUEST_BYTES", 512*1024),
		DefaultWorkloadUser:     env("SIMPLEPOINT_TOOL_RUNTIME_WORKLOAD_USER", "65532:65532"),
		DefaultTmpfsSizeBytes:   envInt64("SIMPLEPOINT_TOOL_RUNTIME_TMPFS_SIZE_BYTES", 64*1024*1024),
		AllowBridgeNetwork:      envBool("SIMPLEPOINT_TOOL_RUNTIME_ALLOW_BRIDGE_NETWORK", false),
		EgressEnabled:           envBool("SIMPLEPOINT_TOOL_RUNTIME_EGRESS_ENABLED", false),
		EgressNetworkName:       strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_EGRESS_NETWORK")),
		EgressProxyURL:          strings.TrimRight(strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_EGRESS_PROXY_URL")), "/"),
		EgressSigningKey:        strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_EGRESS_SIGNING_KEY")),
		MaxEgressHosts:          int(envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_EGRESS_HOSTS", 32)),
		RequiredProtocolVersion: env("SIMPLEPOINT_TOOL_RUNTIME_MCP_PROTOCOL_VERSION", "2025-11-25"),
		MTLSEnabled:             envBool("SIMPLEPOINT_TOOL_RUNTIME_MTLS_ENABLED", false),
		TLSCertificateFile:      strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_TLS_CERT_FILE")),
		TLSPrivateKeyFile:       strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_TLS_KEY_FILE")),
		TLSCAFile:               strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_TLS_CA_FILE")),
		TLSServerName:           env("SIMPLEPOINT_TOOL_RUNTIME_TLS_SERVER_NAME", "tool-runtime"),
		TLSControlServerName:    env("SIMPLEPOINT_TOOL_RUNTIME_TLS_CONTROL_SERVER_NAME", "ai"),
		TLSExpectedAIIdentity:   env("SIMPLEPOINT_TOOL_RUNTIME_TLS_EXPECTED_AI_IDENTITY", "spiffe://open-simplepoint/ai-control-plane"),
		SecretRoot:              env("SIMPLEPOINT_TOOL_RUNTIME_SECRET_ROOT", "/run/simplepoint/runtime-secrets"),
		SecretVolumeName:        env("SIMPLEPOINT_TOOL_RUNTIME_SECRET_VOLUME", "open-simplepoint-runtime-secrets"),
		SecretMountTarget:       env("SIMPLEPOINT_TOOL_RUNTIME_SECRET_MOUNT_TARGET", "/run/secrets/simplepoint"),
		MaxSecretFiles:          int(envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_SECRET_FILES", 16)),
		MaxSecretFileBytes:      envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_SECRET_FILE_BYTES", 16*1024),
		MaxSecretTotalBytes:     envInt64("SIMPLEPOINT_TOOL_RUNTIME_MAX_SECRET_TOTAL_BYTES", 48*1024),
		SupplyChainEnabled:      envBool("SIMPLEPOINT_TOOL_RUNTIME_SUPPLY_CHAIN_ENABLED", false),
		SupplyChainVerifierURL:  strings.TrimRight(strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_URL")), "/"),
		SupplyChainServerName:   env("SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_SERVER_NAME", "tool-image-verifier"),
		SupplyChainIdentity:     env("SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_IDENTITY", "spiffe://open-simplepoint/tool-image-verifier"),
		SupplyChainTimeout:      envDuration("SIMPLEPOINT_TOOL_RUNTIME_IMAGE_VERIFIER_TIMEOUT", defaultRequestTimeout),
		SeccompProfileFile:      env("SIMPLEPOINT_TOOL_RUNTIME_SECCOMP_PROFILE", "/etc/simplepoint/runtime-security/seccomp.json"),
		SeccompRequired:         envBool("SIMPLEPOINT_TOOL_RUNTIME_SECCOMP_REQUIRED", true),
		AppArmorProfile:         env("SIMPLEPOINT_TOOL_RUNTIME_APPARMOR_PROFILE", "open-simplepoint-mcp-workload"),
		AppArmorEnabled:         envBool("SIMPLEPOINT_TOOL_RUNTIME_APPARMOR_ENABLED", true),
		AppArmorRequired:        envBool("SIMPLEPOINT_TOOL_RUNTIME_APPARMOR_REQUIRED", false),
		TLSExpectedGatewayIdentity: env(
			"SIMPLEPOINT_TOOL_RUNTIME_TLS_EXPECTED_GATEWAY_IDENTITY",
			"spiffe://open-simplepoint/mcp-gateway",
		),
		MCPRequestTimeout: envDuration(
			"SIMPLEPOINT_TOOL_RUNTIME_MCP_REQUEST_TIMEOUT",
			5*time.Minute,
		),
		MaxMCPMessageBytes: envInt64(
			"SIMPLEPOINT_TOOL_RUNTIME_MAX_MCP_MESSAGE_BYTES",
			10*1024*1024,
		),
	}
	return cfg, cfg.Validate()
}

// Validate rejects unsafe or internally inconsistent node limits.
func (c Config) Validate() error {
	switch {
	case !c.MTLSEnabled && strings.TrimSpace(c.InternalToken) == "":
		return errors.New("SIMPLEPOINT_TOOL_RUNTIME_INTERNAL_TOKEN is required")
	case !c.MTLSEnabled && len(c.InternalToken) < 24:
		return errors.New("tool runtime internal token must contain at least 24 characters")
	case !safeIdentifier(c.NodeID, 64):
		return errors.New("tool runtime node ID is invalid")
	case !c.MTLSEnabled && c.ControlPlaneURL != "" && len(c.ControlPlaneToken) < 24:
		return errors.New("tool runtime control-plane token must contain at least 24 characters")
	case c.ControlPlaneURL != "" && !validBaseURL(c.ControlPlaneURL):
		return errors.New("tool runtime control-plane URL is invalid")
	case c.ControlPlaneURL != "" && !validBaseURL(c.AdvertiseURL):
		return errors.New("tool runtime advertise URL is invalid")
	case c.ControlPlaneURL == "" && (c.ControlPlaneToken != "" || c.AdvertiseURL != ""):
		return errors.New("tool runtime control-plane URL is required when control-plane settings are present")
	case c.MTLSEnabled && c.ControlPlaneURL != "" &&
		(!isHTTPS(c.ControlPlaneURL) || !isHTTPS(c.AdvertiseURL)):
		return errors.New("tool runtime mTLS URLs must use https")
	case c.MTLSEnabled &&
		(c.TLSCertificateFile == "" ||
			c.TLSPrivateKeyFile == "" ||
			c.TLSCAFile == ""):
		return errors.New("tool runtime mTLS certificate, key, and CA files are required")
	case c.MTLSEnabled &&
		(!safeIdentifier(c.TLSServerName, 253) ||
			!safeIdentifier(c.TLSControlServerName, 253)):
		return errors.New("tool runtime TLS server name is invalid")
	case c.MTLSEnabled && !validSPIFFEIdentity(c.TLSExpectedAIIdentity):
		return errors.New("tool runtime expected AI TLS identity is invalid")
	case c.MTLSEnabled && !validSPIFFEIdentity(c.TLSExpectedGatewayIdentity):
		return errors.New("tool runtime expected Gateway TLS identity is invalid")
	case c.SeccompRequired && !safeAbsolutePath(c.SeccompProfileFile):
		return errors.New("tool runtime seccomp profile path is invalid")
	case c.AppArmorRequired && !safeIdentifier(c.AppArmorProfile, 128):
		return errors.New("tool runtime AppArmor profile is invalid")
	case c.AppArmorRequired && !c.AppArmorEnabled:
		return errors.New("required AppArmor policy cannot be disabled")
	case c.AppArmorProfile != "" && !safeIdentifier(c.AppArmorProfile, 128):
		return errors.New("tool runtime AppArmor profile is invalid")
	case !safeAbsolutePath(c.SecretRoot) ||
		!safeAbsolutePath(c.SecretMountTarget) ||
		c.SecretRoot == c.SecretMountTarget:
		return errors.New("tool runtime secret paths are invalid")
	case !safeIdentifier(c.SecretVolumeName, 255):
		return errors.New("tool runtime secret volume name is invalid")
	case c.MaxSecretFiles <= 0 || c.MaxSecretFiles > 64:
		return errors.New("tool runtime maximum secret file count is invalid")
	case c.MaxSecretFileBytes <= 0 ||
		c.MaxSecretFileBytes > 64*1024 ||
		c.MaxSecretTotalBytes < c.MaxSecretFileBytes ||
		c.MaxSecretTotalBytes > 256*1024:
		return errors.New("tool runtime secret size limits are invalid")
	case c.SupplyChainEnabled &&
		(!c.MTLSEnabled ||
			!isHTTPS(c.SupplyChainVerifierURL) ||
			!validBaseURL(c.SupplyChainVerifierURL) ||
			!safeIdentifier(c.SupplyChainServerName, 253) ||
			!validSPIFFEIdentity(c.SupplyChainIdentity) ||
			c.SupplyChainTimeout < time.Second ||
			c.SupplyChainTimeout > 30*time.Minute):
		return errors.New("tool runtime supply-chain verifier configuration is invalid")
	case c.EgressEnabled &&
		(!safeIdentifier(c.EgressNetworkName, 255) ||
			!validProxyURL(c.EgressProxyURL) ||
			len(c.EgressSigningKey) < 32):
		return errors.New("tool runtime egress configuration is invalid")
	case c.MaxEgressHosts <= 0 || c.MaxEgressHosts > 32:
		return errors.New("tool runtime maximum egress host count is invalid")
	case c.MaxWorkloads <= 0 || c.MaxWorkloads > 10_000:
		return errors.New("tool runtime maximum workload count is invalid")
	case c.MaxReportedImageDigests <= 0 ||
		c.MaxReportedImageDigests > 512:
		return errors.New("tool runtime reported image digest limit is invalid")
	case c.RegistrationRetry <= 0 || c.RegistrationRetry > 10*time.Minute:
		return errors.New("tool runtime registration retry interval is invalid")
	case len(c.NodeLabels) > 64:
		return errors.New("tool runtime node has too many labels")
	case !safeIdentifier(c.Namespace, 128):
		return errors.New("tool runtime namespace is invalid")
	case len(c.AllowedRegistries) == 0:
		return errors.New("at least one OCI registry must be allowed")
	case c.DefaultMemoryBytes < 32*1024*1024 || c.DefaultMemoryBytes > c.MaxMemoryBytes:
		return errors.New("default workload memory is outside the configured range")
	case c.MaxMemoryBytes > 16*1024*1024*1024:
		return errors.New("maximum workload memory exceeds the hard safety ceiling")
	case c.DefaultNanoCPUs <= 0 || c.DefaultNanoCPUs > c.MaxNanoCPUs:
		return errors.New("default workload CPU is outside the configured range")
	case c.MaxNanoCPUs > 16_000_000_000:
		return errors.New("maximum workload CPU exceeds the hard safety ceiling")
	case c.DefaultPidsLimit <= 0 || c.DefaultPidsLimit > c.MaxPidsLimit:
		return errors.New("default workload PID limit is outside the configured range")
	case c.MaxPidsLimit > 4096:
		return errors.New("maximum workload PID limit exceeds the hard safety ceiling")
	case c.RequestTimeout <= 0 || c.RequestTimeout > 10*time.Minute:
		return errors.New("runtime request timeout is invalid")
	case c.MCPRequestTimeout <= 0 || c.MCPRequestTimeout > 24*time.Hour:
		return errors.New("runtime MCP request timeout is invalid")
	case c.MaxMCPMessageBytes <= 0 || c.MaxMCPMessageBytes > 16*1024*1024:
		return errors.New("runtime MCP message size limit is invalid")
	case c.MaxRequestBytes <= 0 || c.MaxRequestBytes > 1024*1024:
		return errors.New("runtime maximum request size is invalid")
	case c.DefaultTmpfsSizeBytes < 1024*1024 || c.DefaultTmpfsSizeBytes > 1024*1024*1024:
		return errors.New("runtime tmpfs limit is invalid")
	}
	return nil
}

func safeAbsolutePath(value string) bool {
	cleaned := filepath.Clean(value)
	return filepath.IsAbs(cleaned) &&
		cleaned == value &&
		cleaned != "/" &&
		!strings.ContainsRune(cleaned, '\x00')
}

func validBaseURL(value string) bool {
	parsed, err := url.Parse(value)
	if err != nil {
		return false
	}
	return (parsed.Scheme == "http" || parsed.Scheme == "https") &&
		parsed.Host != "" &&
		parsed.User == nil &&
		(parsed.Path == "" || parsed.Path == "/") &&
		parsed.RawQuery == "" &&
		parsed.Fragment == ""
}

func isHTTPS(value string) bool {
	parsed, err := url.Parse(value)
	return err == nil && parsed.Scheme == "https"
}

func validProxyURL(value string) bool {
	parsed, err := url.Parse(value)
	return err == nil &&
		parsed.Scheme == "http" &&
		parsed.Hostname() != "" &&
		parsed.Port() != "" &&
		parsed.User == nil &&
		(parsed.Path == "" || parsed.Path == "/") &&
		parsed.RawQuery == "" &&
		parsed.Fragment == ""
}

func validSPIFFEIdentity(value string) bool {
	parsed, err := url.Parse(value)
	return err == nil &&
		parsed.Scheme == "spiffe" &&
		parsed.Host != "" &&
		parsed.User == nil &&
		parsed.RawQuery == "" &&
		parsed.Fragment == ""
}

// NodeTLSIdentity returns the URI SAN required for this stable node identity.
func (c Config) NodeTLSIdentity() string {
	return "spiffe://open-simplepoint/runtime-node/" + c.NodeID
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envBool(name string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func envInt64(name string, fallback int64) int64 {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return fallback
	}
	return parsed
}

func envDuration(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func csvSet(value string) map[string]struct{} {
	result := make(map[string]struct{})
	for item := range strings.SplitSeq(value, ",") {
		if normalized := strings.ToLower(strings.TrimSpace(item)); normalized != "" {
			result[normalized] = struct{}{}
		}
	}
	return result
}

func labelMap(value string) (map[string]string, error) {
	result := make(map[string]string)
	for item := range strings.SplitSeq(value, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		key, labelValue, found := strings.Cut(item, "=")
		key = strings.TrimSpace(key)
		labelValue = strings.TrimSpace(labelValue)
		if !found || !safeIdentifier(key, 128) || len(labelValue) > 512 {
			return nil, errors.New("tool runtime node labels are invalid")
		}
		result[key] = labelValue
	}
	return result, nil
}

func safeIdentifier(value string, maximum int) bool {
	if value == "" || len(value) > maximum {
		return false
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') ||
			(char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') ||
			char == '.' || char == '_' || char == '-' {
			continue
		}
		return false
	}
	return true
}
