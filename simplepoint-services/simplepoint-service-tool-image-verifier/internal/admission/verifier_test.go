package admission

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/config"
)

const testImage = "docker.io/example/mcp@sha256:" +
	"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type runnerCall struct {
	name string
	args []string
}

type fakeRunner struct {
	outputs [][]byte
	errors  []error
	calls   []runnerCall
}

func (r *fakeRunner) Run(
	_ context.Context,
	name string,
	args ...string,
) ([]byte, error) {
	r.calls = append(r.calls, runnerCall{name: name, args: args})
	index := len(r.calls) - 1
	return r.outputs[index], r.errors[index]
}

func TestVerifyAdmitsSignedSBOMWithNoBlockedVulnerabilities(t *testing.T) {
	runner := &fakeRunner{
		outputs: [][]byte{
			[]byte(`[{"critical":{"identity":"workflow"}}]`),
			[]byte(`{"payload":"sbom"}`),
			[]byte(`{"Results":[{"Vulnerabilities":[]}]}`),
		},
		errors: []error{nil, nil, nil},
	}
	verifier := New(testConfig(), runner)
	result := verifier.Verify(context.Background(), testImage)
	if !result.Admitted || !result.SignatureVerified || !result.SBOMVerified {
		t.Fatalf("expected image admission, got %+v", result)
	}
	if len(runner.calls) != 3 {
		t.Fatalf("expected three verification commands, got %d", len(runner.calls))
	}
	if !contains(runner.calls[1].args, "--type", "spdx") {
		t.Fatalf("expected SPDX attestation verification: %v", runner.calls[1].args)
	}
}

func TestVerifyRejectsBeforeScanningWhenSignatureFails(t *testing.T) {
	runner := &fakeRunner{
		outputs: [][]byte{[]byte("no signatures found")},
		errors:  []error{errors.New("exit status 1")},
	}
	result := New(testConfig(), runner).Verify(context.Background(), testImage)
	if result.Admitted || result.SignatureVerified ||
		result.Reason != "image signature verification failed" {
		t.Fatalf("unexpected decision: %+v", result)
	}
	if len(runner.calls) != 1 {
		t.Fatal("vulnerability scanning must not run after signature failure")
	}
}

func TestVerifyRejectsBlockedVulnerabilities(t *testing.T) {
	runner := &fakeRunner{
		outputs: [][]byte{
			[]byte(`[{"critical":{"identity":"workflow"}}]`),
			[]byte(`{"payload":"sbom"}`),
			[]byte(`{"Results":[{"Vulnerabilities":[` +
				`{"Severity":"HIGH"},{"Severity":"CRITICAL"}]}]}`),
		},
		errors: []error{nil, nil, nil},
	}
	result := New(testConfig(), runner).Verify(context.Background(), testImage)
	if result.Admitted || result.VulnerabilityCounts["HIGH"] != 1 ||
		result.VulnerabilityCounts["CRITICAL"] != 1 {
		t.Fatalf("unexpected decision: %+v", result)
	}
}

func TestVerifyCachesDecisionsByDigest(t *testing.T) {
	runner := &fakeRunner{
		outputs: [][]byte{
			[]byte(`[{"critical":{"identity":"workflow"}}]`),
			[]byte(`{"payload":"sbom"}`),
			[]byte(`{"Results":[]}`),
		},
		errors: []error{nil, nil, nil},
	}
	verifier := New(testConfig(), runner)
	first := verifier.Verify(context.Background(), testImage)
	second := verifier.Verify(context.Background(), testImage)
	if !first.Admitted || !second.Admitted || len(runner.calls) != 3 {
		t.Fatal("expected the second decision to use the digest cache")
	}
}

func TestValidImageReferenceRejectsMutableAndUnsafeReferences(t *testing.T) {
	for _, value := range []string{
		"docker.io/example/mcp:latest",
		"docker.io/example/../mcp@sha256:" + strings.Repeat("a", 64),
		"docker.io/example/mcp@sha512:" + strings.Repeat("a", 64),
	} {
		if ValidImageReference(value) {
			t.Fatalf("expected %q to be rejected", value)
		}
	}
}

func testConfig() config.Config {
	return config.Config{
		RequestTimeout:             time.Minute,
		MaxConcurrentVerifications: 2,
		CacheTTL:                   10 * time.Minute,
		SignatureMode:              "keyless",
		CertificateIdentityRegexp:  "^https://github.com/example/.+$",
		CertificateOIDCIssuer:      "https://token.actions.githubusercontent.com",
		RequireSBOM:                true,
		SBOMType:                   "spdx",
		BlockedSeverities:          []string{"HIGH", "CRITICAL"},
		CosignBinary:               "/usr/local/bin/cosign",
		TrivyBinary:                "/usr/local/bin/trivy",
		TrivyCacheDirectory:        "/var/cache/trivy",
		PolicyHash:                 "sha256:test-policy",
	}
}

func contains(values []string, expected ...string) bool {
	for index := 0; index <= len(values)-len(expected); index++ {
		matches := true
		for offset := range expected {
			if values[index+offset] != expected[offset] {
				matches = false
				break
			}
		}
		if matches {
			return true
		}
	}
	return false
}
