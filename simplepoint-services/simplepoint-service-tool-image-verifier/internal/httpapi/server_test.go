package httpapi

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/admission"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/config"
)

const artifactReference = "registry.example.com/skills/example@sha256:" +
	"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type fakeVerifier struct {
	artifactCalls int
}

func (f *fakeVerifier) Verify(
	_ context.Context,
	image string,
) admission.Result {
	return admission.Result{Image: image}
}

func (f *fakeVerifier) VerifyArtifact(
	_ context.Context,
	artifact string,
) admission.ArtifactResult {
	f.artifactCalls++
	return admission.ArtifactResult{
		Artifact:          artifact,
		Admitted:          true,
		SignatureVerified: true,
		PolicyHash:        "sha256:policy",
		CheckedAt:         time.Now(),
	}
}

func TestArtifactAdmissionRequiresExactAIIdentity(t *testing.T) {
	cfg := config.Config{
		MTLSEnabled:        true,
		MaxRequestBytes:    16 * 1024,
		ExpectedAIIdentity: "spiffe://open-simplepoint/ai-control-plane",
	}
	verifier := &fakeVerifier{}
	handler := New(cfg, verifier, slog.New(slog.NewTextHandler(io.Discard, nil)))

	accepted := artifactRequest(t, cfg.ExpectedAIIdentity)
	acceptedResponse := httptest.NewRecorder()
	handler.ServeHTTP(acceptedResponse, accepted)
	if acceptedResponse.Code != http.StatusOK || verifier.artifactCalls != 1 {
		t.Fatalf(
			"expected AI Artifact admission, got %d",
			acceptedResponse.Code,
		)
	}

	rejected := artifactRequest(
		t,
		"spiffe://open-simplepoint/runtime-node/node-a",
	)
	rejectedResponse := httptest.NewRecorder()
	handler.ServeHTTP(rejectedResponse, rejected)
	if rejectedResponse.Code != http.StatusUnauthorized ||
		verifier.artifactCalls != 1 {
		t.Fatalf(
			"expected runtime identity rejection, got %d",
			rejectedResponse.Code,
		)
	}
}

func artifactRequest(t *testing.T, identity string) *http.Request {
	t.Helper()
	uri, err := url.Parse(identity)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(
		http.MethodPost,
		"/internal/v1/artifacts/verify",
		strings.NewReader(`{"artifact":"`+artifactReference+`"}`),
	)
	request.TLS = &tls.ConnectionState{
		VerifiedChains: [][]*x509.Certificate{{
			{URIs: []*url.URL{uri}},
		}},
	}
	return request
}
