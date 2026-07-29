package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestValidateAcceptsKeylessPolicy(t *testing.T) {
	cfg := validConfig()
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected policy to be valid: %v", err)
	}
}

func TestValidateRejectsUnsafeKeylessIdentity(t *testing.T) {
	cfg := validConfig()
	cfg.CertificateIdentityRegexp = "["
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected malformed identity regexp to be rejected")
	}
	cfg = validConfig()
	cfg.CertificateOIDCIssuer = "http://issuer.example.com"
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected insecure OIDC issuer to be rejected")
	}
}

func TestPolicyHashChangesWithPublicKey(t *testing.T) {
	directory := t.TempDir()
	keyFile := filepath.Join(directory, "cosign.pub")
	if err := os.WriteFile(keyFile, []byte("first-key"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := validConfig()
	cfg.SignatureMode = "key"
	cfg.CosignPublicKeyFile = keyFile
	first, err := cfg.policyHash()
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(keyFile, []byte("second-key"), 0o600); err != nil {
		t.Fatal(err)
	}
	second, err := cfg.policyHash()
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("expected public key rotation to change the policy hash")
	}
}

func validConfig() Config {
	return Config{
		Address:                    ":2893",
		RequestTimeout:             time.Minute,
		ShutdownTimeout:            10 * time.Second,
		MaxRequestBytes:            16 * 1024,
		MaxConcurrentVerifications: 2,
		CacheTTL:                   10 * time.Minute,
		MTLSEnabled:                true,
		TLSCertificateFile:         "/run/pki/tls.crt",
		TLSPrivateKeyFile:          "/run/pki/tls.key",
		TLSCAFile:                  "/run/pki/ca.crt",
		TLSIdentity:                "spiffe://open-simplepoint/tool-image-verifier",
		ExpectedNodeIdentityPrefix: "spiffe://open-simplepoint/runtime-node/",
		ExpectedAIIdentity:         "spiffe://open-simplepoint/ai-control-plane",
		SignatureMode:              "keyless",
		CertificateIdentityRegexp:  "^https://github.com/example/repository/.github/workflows/.+$",
		CertificateOIDCIssuer:      "https://token.actions.githubusercontent.com",
		RequireSBOM:                true,
		SBOMType:                   "spdx",
		BlockedSeverities:          []string{"HIGH", "CRITICAL"},
		CosignBinary:               "/usr/local/bin/cosign",
		TrivyBinary:                "/usr/local/bin/trivy",
		TrivyCacheDirectory:        "/var/cache/trivy",
	}
}
