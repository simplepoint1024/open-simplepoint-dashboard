package config

import (
	"strings"
	"testing"
	"time"
)

func TestValidateRejectsMissingOrShortToken(t *testing.T) {
	cfg := validConfig()
	cfg.InternalToken = ""
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected a missing token to be rejected")
	}
	cfg.InternalToken = "short"
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected a short token to be rejected")
	}
}

func TestValidateRejectsUnsafeLimits(t *testing.T) {
	cfg := validConfig()
	cfg.MaxMemoryBytes = 17 * 1024 * 1024 * 1024
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected excessive memory to be rejected")
	}
	cfg = validConfig()
	cfg.NodeID = strings.Repeat("x", 65)
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected an oversized node ID to be rejected")
	}
	cfg = validConfig()
	cfg.SecretRoot = "/"
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected an unsafe secret root to be rejected")
	}
	cfg = validConfig()
	cfg.MaxSecretTotalBytes = cfg.MaxSecretFileBytes - 1
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected an invalid secret size limit to be rejected")
	}
	cfg = validConfig()
	cfg.EgressEnabled = true
	cfg.EgressNetworkName = "runtime-egress"
	cfg.EgressProxyURL = "http://tool-egress-proxy:2892"
	cfg.EgressSigningKey = "short"
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected a weak egress signing key to be rejected")
	}
	cfg = validConfig()
	cfg.SupplyChainEnabled = true
	cfg.MTLSEnabled = true
	cfg.SupplyChainVerifierURL = "http://tool-image-verifier:2893"
	cfg.SupplyChainServerName = "tool-image-verifier"
	cfg.SupplyChainIdentity = "spiffe://open-simplepoint/tool-image-verifier"
	cfg.SupplyChainTimeout = time.Minute
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected an insecure image verifier URL to be rejected")
	}
	cfg = validConfig()
	cfg.InternalServiceRoutes = map[string]string{
		"postgres:not-a-port": "runtime-postgres",
	}
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected an invalid endpoint route to be rejected")
	}
}

func TestRouteMapParsesExactEndpointToNetworkMappings(t *testing.T) {
	routes := routeMap(
		"POSTGRES:5432=Open-SimplePoint-Runtime-Postgres," +
			"db.example.com:6432=runtime-db",
	)
	if routes["postgres:5432"] !=
		"open-simplepoint-runtime-postgres" ||
		routes["db.example.com:6432"] != "runtime-db" {
		t.Fatalf("unexpected endpoint route map: %#v", routes)
	}
}

func TestValidateAcceptsControlPlaneConfiguration(t *testing.T) {
	cfg := validConfig()
	cfg.ControlPlaneURL = "http://ai:2888"
	cfg.ControlPlaneToken = "test-control-token-0123456789"
	cfg.AdvertiseURL = "http://tool-runtime:2891"
	cfg.NodeLabels = map[string]string{
		"environment":  "test",
		"orchestrator": "compose",
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected control-plane configuration to be valid: %v", err)
	}
}

func TestValidateRejectsPartialControlPlaneConfiguration(t *testing.T) {
	cfg := validConfig()
	cfg.ControlPlaneURL = "http://ai:2888"
	cfg.ControlPlaneToken = "short"
	cfg.AdvertiseURL = "http://tool-runtime:2891"
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected a short control-plane token to be rejected")
	}

	cfg = validConfig()
	cfg.ControlPlaneToken = "test-control-token-0123456789"
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected control-plane settings without a URL to be rejected")
	}
}

func TestValidateAcceptsMutualTLSWithoutSharedTokens(t *testing.T) {
	cfg := validConfig()
	cfg.InternalToken = ""
	cfg.ControlPlaneToken = ""
	cfg.ControlPlaneURL = "https://ai:2889"
	cfg.AdvertiseURL = "https://tool-runtime:2891"
	cfg.MTLSEnabled = true
	cfg.TLSCertificateFile = "/run/pki/tls.crt"
	cfg.TLSPrivateKeyFile = "/run/pki/tls.key"
	cfg.TLSCAFile = "/run/pki/ca.crt"
	cfg.TLSServerName = "tool-runtime"
	cfg.TLSControlServerName = "ai"
	cfg.TLSExpectedAIIdentity = "spiffe://open-simplepoint/ai-control-plane"

	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected mutual TLS configuration to be valid: %v", err)
	}
	if cfg.NodeTLSIdentity() !=
		"spiffe://open-simplepoint/runtime-node/test-node" {
		t.Fatalf("unexpected node TLS identity: %q", cfg.NodeTLSIdentity())
	}
}

func TestValidateAcceptsSupplyChainVerifierOverMutualTLS(t *testing.T) {
	cfg := validConfig()
	cfg.InternalToken = ""
	cfg.MTLSEnabled = true
	cfg.TLSCertificateFile = "/run/pki/tls.crt"
	cfg.TLSPrivateKeyFile = "/run/pki/tls.key"
	cfg.TLSCAFile = "/run/pki/ca.crt"
	cfg.TLSServerName = "tool-runtime"
	cfg.TLSControlServerName = "ai"
	cfg.TLSExpectedAIIdentity = "spiffe://open-simplepoint/ai-control-plane"
	cfg.SupplyChainEnabled = true
	cfg.SupplyChainVerifierURL = "https://tool-image-verifier:2893"
	cfg.SupplyChainServerName = "tool-image-verifier"
	cfg.SupplyChainIdentity = "spiffe://open-simplepoint/tool-image-verifier"
	cfg.SupplyChainTimeout = time.Minute
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected supply-chain verifier configuration to be valid: %v", err)
	}
}

func TestValidateRejectsInsecureMutualTLSURLs(t *testing.T) {
	cfg := validConfig()
	cfg.InternalToken = ""
	cfg.ControlPlaneURL = "http://ai:2888"
	cfg.AdvertiseURL = "https://tool-runtime:2891"
	cfg.MTLSEnabled = true
	cfg.TLSCertificateFile = "/run/pki/tls.crt"
	cfg.TLSPrivateKeyFile = "/run/pki/tls.key"
	cfg.TLSCAFile = "/run/pki/ca.crt"
	cfg.TLSServerName = "tool-runtime"
	cfg.TLSControlServerName = "ai"
	cfg.TLSExpectedAIIdentity = "spiffe://open-simplepoint/ai-control-plane"

	if err := cfg.Validate(); err == nil {
		t.Fatal("expected an insecure mTLS control-plane URL to be rejected")
	}
}

func TestLabelMapRejectsMalformedLabels(t *testing.T) {
	if _, err := labelMap("environment=dev,broken"); err == nil {
		t.Fatal("expected malformed labels to be rejected")
	}
}

func validConfig() Config {
	cfg, err := Load()
	if err == nil {
		return cfg
	}
	return Config{
		Address:                    ":2891",
		NodeID:                     "test-node",
		InternalToken:              "test-runtime-token-0123456789",
		NodeLabels:                 map[string]string{},
		MaxWorkloads:               64,
		MaxReportedImageDigests:    defaultReportedImages,
		RegistrationRetry:          defaultRegistrationRetry,
		Namespace:                  "open-simplepoint-tool-",
		AllowedRegistries:          map[string]struct{}{"docker.io": {}},
		RequireDigest:              true,
		RequireMCPLabels:           true,
		DefaultMemoryBytes:         defaultMemoryBytes,
		MaxMemoryBytes:             defaultMaxMemoryBytes,
		DefaultNanoCPUs:            defaultNanoCPUs,
		MaxNanoCPUs:                defaultMaxNanoCPUs,
		DefaultPidsLimit:           defaultPidsLimit,
		MaxPidsLimit:               defaultMaxPidsLimit,
		RequestTimeout:             defaultRequestTimeout,
		ShutdownTimeout:            defaultShutdownTimeout,
		MaxRequestBytes:            64 * 1024,
		DefaultWorkloadUser:        "65532:65532",
		DefaultTmpfsSizeBytes:      64 * 1024 * 1024,
		MaxEgressHosts:             32,
		RequiredProtocolVersion:    "2025-11-25",
		SecretRoot:                 "/run/simplepoint/runtime-secrets",
		SecretVolumeName:           "open-simplepoint-runtime-secrets",
		SecretMountTarget:          "/run/secrets/simplepoint",
		MaxSecretFiles:             16,
		MaxSecretFileBytes:         16 * 1024,
		MaxSecretTotalBytes:        48 * 1024,
		SeccompProfileFile:         "/etc/simplepoint/runtime-security/seccomp.json",
		SeccompRequired:            true,
		AppArmorProfile:            "open-simplepoint-mcp-workload",
		TLSExpectedGatewayIdentity: "spiffe://open-simplepoint/mcp-gateway",
		MCPRequestTimeout:          5 * time.Minute,
		MaxMCPMessageBytes:         10 * 1024 * 1024,
	}
}
