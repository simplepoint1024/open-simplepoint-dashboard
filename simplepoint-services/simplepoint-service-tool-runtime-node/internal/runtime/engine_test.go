package runtime

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/moby/moby/api/types/image"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/supplychain"
)

type rejectingAdmissionVerifier struct{}

func (rejectingAdmissionVerifier) Verify(
	_ context.Context,
	image string,
) (supplychain.Result, error) {
	return supplychain.Result{
		Image:      image,
		Admitted:   false,
		PolicyHash: "sha256:" + repeat("a", 64),
		Reason:     "signed SBOM attestation verification failed",
	}, nil
}

func TestValidateImageReferenceRequiresDigestAndAllowedRegistry(t *testing.T) {
	engine := &Engine{config: testConfig()}
	if err := engine.validateImageReference("somesimpled/tool:latest"); err == nil {
		t.Fatal("expected a mutable tag to be rejected")
	}
	if err := engine.validateImageReference(
		"evil.example/tool@sha256:" + repeat("a", 64),
	); err == nil {
		t.Fatal("expected a disallowed registry to be rejected")
	}
	if err := engine.validateImageReference(
		"somesimpled/tool@sha256:" + repeat("a", 64),
	); err != nil {
		t.Fatalf("expected Docker Hub digest to be allowed: %v", err)
	}
}

func TestPrepareFailsClosedBeforePullWhenSupplyChainAdmissionIsDenied(
	t *testing.T,
) {
	cfg := testConfig()
	cfg.SupplyChainEnabled = true
	engine := &Engine{
		config:            cfg,
		admissionVerifier: rejectingAdmissionVerifier{},
	}
	_, err := engine.Prepare(
		context.Background(),
		"somesimpled/tool@sha256:"+repeat("a", 64),
	)
	if err == nil {
		t.Fatal("expected denied supply-chain admission to prevent image pull")
	}
}

func TestCachedImageDigestsAreUniqueSortedAndBounded(t *testing.T) {
	first := "sha256:" + repeat("a", 64)
	second := "sha256:" + repeat("b", 64)
	got := cachedImageDigests([]image.Summary{
		{RepoDigests: []string{"registry.example/tool@" + second}},
		{RepoDigests: []string{
			"registry.example/tool@" + first,
			"registry.example/other@" + second,
			"registry.example/invalid@sha256:not-a-digest",
		}},
	}, 1)
	if len(got) != 1 || got[0] != first {
		t.Fatalf("unexpected cached digest set: %#v", got)
	}
}

func TestNormalizeForcesBoundedSandboxValues(t *testing.T) {
	engine := &Engine{config: testConfig()}
	request, err := engine.normalize(StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   1,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
	})
	if err != nil {
		t.Fatalf("normalize request: %v", err)
	}
	if request.NetworkMode != networkNone {
		t.Fatalf("expected no network, got %q", request.NetworkMode)
	}
	if request.MemoryBytes != testConfig().DefaultMemoryBytes {
		t.Fatalf("unexpected memory default: %d", request.MemoryBytes)
	}
	if request.PidsLimit != testConfig().DefaultPidsLimit {
		t.Fatalf("unexpected PID default: %d", request.PidsLimit)
	}
}

func TestLoadSandboxPolicyCanonicalizesAndHashesProfile(t *testing.T) {
	profile := filepath.Join(t.TempDir(), "seccomp.json")
	if err := os.WriteFile(
		profile,
		[]byte(`{"syscalls":[],"defaultAction":"SCMP_ACT_ALLOW"}`),
		0o600,
	); err != nil {
		t.Fatalf("write seccomp profile: %v", err)
	}
	cfg := testConfig()
	cfg.SeccompProfileFile = profile
	policy, err := loadSandboxPolicy(cfg)
	if err != nil {
		t.Fatalf("load seccomp profile: %v", err)
	}
	if policy.seccompJSON !=
		`{"defaultAction":"SCMP_ACT_ALLOW","syscalls":[]}` {
		t.Fatalf("unexpected canonical profile: %s", policy.seccompJSON)
	}
	if len(policy.seccompHash) != 71 {
		t.Fatalf("unexpected seccomp profile hash: %q", policy.seccompHash)
	}
}

func TestNormalizeRejectsBridgeAndUnsafeEnvironment(t *testing.T) {
	engine := &Engine{config: testConfig()}
	_, err := engine.normalize(StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   1,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
		NetworkMode:    networkBridge,
	})
	if err == nil {
		t.Fatal("expected bridge networking to be rejected")
	}
	_, err = engine.normalize(StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   1,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
		Environment:    map[string]string{"bad-key": "value"},
	})
	if err == nil {
		t.Fatal("expected unsafe environment name to be rejected")
	}
	_, err = engine.normalize(StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   1,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
		Environment:    map[string]string{"REMOTE_API_TOKEN": "do-not-inject"},
	})
	if err == nil {
		t.Fatal("expected environment-based secrets to be rejected")
	}
	_, err = engine.normalize(StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   1,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
		Environment:    map[string]string{secretDirectoryEnv: "/tmp/escape"},
	})
	if err == nil {
		t.Fatal("expected the reserved secret directory variable to be rejected")
	}
	_, err = engine.normalize(StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   1,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
		Environment:    map[string]string{"HTTP_PROXY": "http://bypass:8080"},
	})
	if err == nil {
		t.Fatal("expected a caller-controlled proxy variable to be rejected")
	}
}

func TestNormalizeAllowsOnlyPolicyBoundEgress(t *testing.T) {
	cfg := testConfig()
	cfg.EgressEnabled = true
	cfg.EgressNetworkName = "open-simplepoint-runtime-egress"
	cfg.EgressProxyURL = "http://tool-egress-proxy:2892"
	cfg.EgressSigningKey = "test-egress-signing-key-0123456789"
	engine := &Engine{config: cfg}
	request, err := engine.normalize(StartRequest{
		WorkloadID:      "workload-1",
		LeaseID:         "lease-1",
		FencingToken:    1,
		ExecutionID:     "execution-1",
		Image:           "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds:  60,
		NetworkMode:     networkEgress,
		EgressAllowlist: []string{"API.EXAMPLE.COM.", "*.files.example.com"},
	})
	if err != nil {
		t.Fatalf("expected egress policy to be accepted: %v", err)
	}
	if request.EgressAllowlist[0] != "*.files.example.com" ||
		request.EgressAllowlist[1] != "api.example.com" {
		t.Fatalf("unexpected egress normalization: %v", request.EgressAllowlist)
	}
	request.NetworkMode = networkNone
	if _, err = engine.normalize(request); err == nil {
		t.Fatal("expected egress hosts without egress mode to be rejected")
	}
}

func TestPrepareAndCleanupSecretsUseIsolatedReadOnlyFiles(t *testing.T) {
	cfg := testConfig()
	cfg.SecretRoot = t.TempDir()
	engine := &Engine{config: cfg}
	request, err := engine.normalize(StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   7,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
		Secrets: []SecretFile{
			{Name: "zeta", Value: "second"},
			{Name: "alpha", Value: "first"},
		},
	})
	if err != nil {
		t.Fatalf("normalize secret request: %v", err)
	}
	subpath, err := engine.prepareSecrets(request)
	if err != nil {
		t.Fatalf("prepare secrets: %v", err)
	}
	if subpath != filepath.Join("test-node", "workload-1-7") {
		t.Fatalf("unexpected secret subpath: %q", subpath)
	}
	secretPath := filepath.Join(cfg.SecretRoot, subpath, "alpha")
	value, err := os.ReadFile(secretPath)
	if err != nil {
		t.Fatalf("read prepared secret: %v", err)
	}
	if string(value) != "first" {
		t.Fatal("prepared secret content does not match")
	}
	info, err := os.Stat(secretPath)
	if err != nil {
		t.Fatalf("stat prepared secret: %v", err)
	}
	if info.Mode().Perm() != 0o400 {
		t.Fatalf("unexpected secret mode: %o", info.Mode().Perm())
	}
	if err = engine.cleanupSecrets("workload-1", 7); err != nil {
		t.Fatalf("cleanup secrets: %v", err)
	}
	if _, err = os.Stat(filepath.Dir(secretPath)); !os.IsNotExist(err) {
		t.Fatal("expected the workload secret directory to be removed")
	}
}

func TestNormalizeRejectsInvalidSecretFiles(t *testing.T) {
	engine := &Engine{config: testConfig()}
	base := StartRequest{
		WorkloadID:     "workload-1",
		LeaseID:        "lease-1",
		FencingToken:   1,
		ExecutionID:    "execution-1",
		Image:          "somesimpled/tool@sha256:" + repeat("a", 64),
		TimeoutSeconds: 60,
	}
	base.Secrets = []SecretFile{{Name: "../escape", Value: "value"}}
	if _, err := engine.normalize(base); err == nil {
		t.Fatal("expected a traversal secret name to be rejected")
	}
	base.Secrets = []SecretFile{
		{Name: "duplicate", Value: "one"},
		{Name: "duplicate", Value: "two"},
	}
	if _, err := engine.normalize(base); err == nil {
		t.Fatal("expected duplicate secret names to be rejected")
	}
	base.Secrets = []SecretFile{{Name: "empty", Value: ""}}
	if _, err := engine.normalize(base); err == nil {
		t.Fatal("expected an empty secret value to be rejected")
	}
}

func testConfig() config.Config {
	return config.Config{
		NodeID:                  "test-node",
		MaxReportedImageDigests: 256,
		Namespace:               "open-simplepoint-tool-",
		AllowedRegistries:       map[string]struct{}{"docker.io": {}},
		RequireDigest:           true,
		RequireMCPLabels:        true,
		DefaultMemoryBytes:      256 * 1024 * 1024,
		MaxMemoryBytes:          2 * 1024 * 1024 * 1024,
		DefaultNanoCPUs:         500_000_000,
		MaxNanoCPUs:             4_000_000_000,
		DefaultPidsLimit:        128,
		MaxPidsLimit:            512,
		DefaultWorkloadUser:     "65532:65532",
		DefaultTmpfsSizeBytes:   64 * 1024 * 1024,
		MaxEgressHosts:          32,
		RequiredProtocolVersion: "2025-11-25",
		SecretRoot:              "/run/simplepoint/runtime-secrets",
		SecretVolumeName:        "open-simplepoint-runtime-secrets",
		SecretMountTarget:       "/run/secrets/simplepoint",
		MaxSecretFiles:          16,
		MaxSecretFileBytes:      16 * 1024,
		MaxSecretTotalBytes:     48 * 1024,
	}
}

func repeat(value string, count int) string {
	result := ""
	for range count {
		result += value
	}
	return result
}
