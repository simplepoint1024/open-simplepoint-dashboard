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

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/admission"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/config"
)

const imageReference = "registry.example.com/tools/example@sha256:" +
	"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type fakeVerifier struct {
	imageCalls int
}

func (f *fakeVerifier) Verify(
	_ context.Context,
	image string,
) admission.Result {
	f.imageCalls++
	return admission.Result{Image: image, Admitted: true}
}

func TestImageAdmissionRequiresRuntimeIdentity(t *testing.T) {
	cfg := config.Config{
		MTLSEnabled:                true,
		MaxRequestBytes:            16 * 1024,
		ExpectedNodeIdentityPrefix: "spiffe://open-simplepoint/runtime-node/",
	}
	verifier := &fakeVerifier{}
	handler := New(cfg, verifier, slog.New(slog.NewTextHandler(io.Discard, nil)))

	accepted := imageRequest(t, cfg.ExpectedNodeIdentityPrefix+"node-a")
	acceptedResponse := httptest.NewRecorder()
	handler.ServeHTTP(acceptedResponse, accepted)
	if acceptedResponse.Code != http.StatusOK || verifier.imageCalls != 1 {
		t.Fatalf(
			"expected Runtime image admission, got %d",
			acceptedResponse.Code,
		)
	}

	rejected := imageRequest(
		t,
		"spiffe://open-simplepoint/ai-control-plane",
	)
	rejectedResponse := httptest.NewRecorder()
	handler.ServeHTTP(rejectedResponse, rejected)
	if rejectedResponse.Code != http.StatusUnauthorized ||
		verifier.imageCalls != 1 {
		t.Fatalf(
			"expected control-plane identity rejection, got %d",
			rejectedResponse.Code,
		)
	}
}

func imageRequest(t *testing.T, identity string) *http.Request {
	t.Helper()
	uri, err := url.Parse(identity)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(
		http.MethodPost,
		"/internal/v1/images/verify",
		strings.NewReader(`{"image":"`+imageReference+`"}`),
	)
	request.TLS = &tls.ConnectionState{
		VerifiedChains: [][]*x509.Certificate{{
			{URIs: []*url.URL{uri}},
		}},
	}
	return request
}

func TestRetiredArtifactEndpointIsNotServed(t *testing.T) {
	verifier := &fakeVerifier{}
	handler := New(config.Config{}, verifier, slog.New(slog.NewTextHandler(io.Discard, nil)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/internal/v1/artifacts/verify", nil))
	if response.Code != http.StatusNotFound || verifier.imageCalls != 0 {
		t.Fatalf("retired endpoint must return 404 without verification: %d", response.Code)
	}
}
