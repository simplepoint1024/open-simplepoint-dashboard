package httpapi

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	runtimeapi "github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/runtime"
)

type fencedRuntime struct {
	startError error
}

func (r fencedRuntime) NodeStatus(context.Context) (runtimeapi.NodeStatus, error) {
	return runtimeapi.NodeStatus{Status: "UP"}, nil
}

func (r fencedRuntime) Prepare(
	context.Context,
	string,
) (runtimeapi.ImageStatus, error) {
	return runtimeapi.ImageStatus{}, nil
}

func (r fencedRuntime) ProbeMCP(
	context.Context,
	runtimeapi.StartRequest,
) (runtimeapi.MCPProbeReport, error) {
	return runtimeapi.MCPProbeReport{
		ProtocolVersion: "2025-11-25",
		ToolsSupported:  true,
		ToolCount:       2,
	}, nil
}

func (r fencedRuntime) Start(
	_ context.Context,
	request runtimeapi.StartRequest,
) (runtimeapi.WorkloadStatus, error) {
	if r.startError != nil {
		return runtimeapi.WorkloadStatus{}, r.startError
	}
	return runtimeapi.WorkloadStatus{
		WorkloadID:   request.WorkloadID,
		LeaseID:      request.LeaseID,
		FencingToken: request.FencingToken,
		State:        "running",
	}, nil
}

func (r fencedRuntime) Status(
	context.Context,
	string,
	string,
	int64,
) (runtimeapi.WorkloadStatus, error) {
	return runtimeapi.WorkloadStatus{}, nil
}

func (r fencedRuntime) Stop(
	context.Context,
	string,
	string,
	int64,
) (runtimeapi.WorkloadStatus, error) {
	return runtimeapi.WorkloadStatus{}, nil
}

func (r fencedRuntime) Delete(
	context.Context,
	string,
	string,
	int64,
) error {
	return nil
}

func (r fencedRuntime) ExchangeMCP(
	_ context.Context,
	_ string,
	_ string,
	_ int64,
	sessionID string,
	_ []byte,
) (runtimeapi.MCPExchangeResult, error) {
	if sessionID == "" {
		sessionID = "session-1"
	}
	return runtimeapi.MCPExchangeResult{
		SessionID: sessionID,
		Message:   []byte(`{"jsonrpc":"2.0","id":1,"result":{}}`),
	}, nil
}

func (r fencedRuntime) MCPEvents(
	context.Context,
	string,
	string,
	int64,
	string,
) (runtimeapi.MCPEventStream, error) {
	return runtimeapi.MCPEventStream{}, runtimeapi.ErrMCPSessionNotFound
}

func (r fencedRuntime) CloseMCPSession(
	context.Context,
	string,
	string,
	int64,
	string,
) error {
	return nil
}

func TestStartRequiresMatchingLeaseFence(t *testing.T) {
	handler := testHandler(fencedRuntime{})
	body := []byte(`{
		"workloadId":"workload-1",
		"leaseId":"lease-1",
		"fencingToken":2,
		"executionId":"execution-1",
		"image":"somesimpled/tool@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"timeoutSeconds":30
	}`)

	missing := httptest.NewRequest(
		http.MethodPost,
		"/internal/v1/workloads",
		bytes.NewReader(body),
	)
	missing.Header.Set("X-SimplePoint-Runtime-Token", testRuntimeToken)
	missingResult := httptest.NewRecorder()
	handler.ServeHTTP(missingResult, missing)
	if missingResult.Code != http.StatusBadRequest {
		t.Fatalf("expected missing fence to return 400, got %d", missingResult.Code)
	}

	request := httptest.NewRequest(
		http.MethodPost,
		"/internal/v1/workloads",
		bytes.NewReader(body),
	)
	request.Header.Set("X-SimplePoint-Runtime-Token", testRuntimeToken)
	request.Header.Set("X-SimplePoint-Runtime-Lease-Id", "lease-1")
	request.Header.Set("X-SimplePoint-Runtime-Fencing-Token", "2")
	result := httptest.NewRecorder()
	handler.ServeHTTP(result, request)
	if result.Code != http.StatusCreated {
		t.Fatalf("expected a matching fence to return 201, got %d", result.Code)
	}
}

func TestMCPProbeUsesControlPlaneAuthenticationWithoutLeaseFence(t *testing.T) {
	handler := testHandler(fencedRuntime{})
	body := []byte(`{
		"image":"somesimpled/tool@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"transport":"stdio",
		"timeoutSeconds":30
	}`)
	request := httptest.NewRequest(
		http.MethodPost,
		"/internal/v1/mcp/probe",
		bytes.NewReader(body),
	)
	request.Header.Set("X-SimplePoint-Runtime-Token", testRuntimeToken)
	result := httptest.NewRecorder()

	handler.ServeHTTP(result, request)

	if result.Code != http.StatusOK {
		t.Fatalf("expected MCP probe to return 200, got %d", result.Code)
	}
	if !bytes.Contains(result.Body.Bytes(), []byte(`"toolsSupported":true`)) {
		t.Fatalf("expected a sanitized MCP probe report, got %s", result.Body.String())
	}
}

func TestStartMapsFencedRuntimeToConflict(t *testing.T) {
	handler := testHandler(fencedRuntime{startError: runtimeapi.ErrFenced})
	body := []byte(`{
		"workloadId":"workload-1",
		"leaseId":"lease-1",
		"fencingToken":1,
		"executionId":"execution-1",
		"image":"somesimpled/tool@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"timeoutSeconds":30
	}`)
	request := httptest.NewRequest(
		http.MethodPost,
		"/internal/v1/workloads",
		bytes.NewReader(body),
	)
	request.Header.Set("X-SimplePoint-Runtime-Token", testRuntimeToken)
	request.Header.Set("X-SimplePoint-Runtime-Lease-Id", "lease-1")
	request.Header.Set("X-SimplePoint-Runtime-Fencing-Token", "1")
	result := httptest.NewRecorder()

	handler.ServeHTTP(result, request)

	if result.Code != http.StatusConflict {
		t.Fatalf("expected a fenced workload to return 409, got %d", result.Code)
	}
}

func TestPrivateAPIRequiresExpectedMutualTLSIdentity(t *testing.T) {
	expected := "spiffe://open-simplepoint/ai-control-plane"
	handler := New(
		config.Config{
			MTLSEnabled:           true,
			TLSExpectedAIIdentity: expected,
			RequestTimeout:        10_000_000_000,
			MaxRequestBytes:       64 * 1024,
			MaxMCPMessageBytes:    64 * 1024,
		},
		fencedRuntime{},
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	missing := httptest.NewRequest(
		http.MethodGet,
		"/internal/v1/node/status",
		nil,
	)
	missingResult := httptest.NewRecorder()
	handler.ServeHTTP(missingResult, missing)
	if missingResult.Code != http.StatusUnauthorized {
		t.Fatalf(
			"expected a missing client identity to return 401, got %d",
			missingResult.Code,
		)
	}

	identity, err := url.Parse(expected)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(
		http.MethodGet,
		"/internal/v1/node/status",
		nil,
	)
	request.TLS = &tls.ConnectionState{
		VerifiedChains: [][]*x509.Certificate{{
			{URIs: []*url.URL{identity}},
		}},
	}
	result := httptest.NewRecorder()
	handler.ServeHTTP(result, request)
	if result.Code != http.StatusOK {
		t.Fatalf(
			"expected the AI client identity to return 200, got %d",
			result.Code,
		)
	}
}

func TestMCPBridgeRequiresGatewayIdentityAndLeaseFence(t *testing.T) {
	expected := "spiffe://open-simplepoint/mcp-gateway"
	handler := New(
		config.Config{
			MTLSEnabled:                true,
			TLSExpectedGatewayIdentity: expected,
			MCPRequestTimeout:          10_000_000_000,
			MaxMCPMessageBytes:         64 * 1024,
		},
		fencedRuntime{},
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	body := []byte(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`)
	unauthorized := httptest.NewRequest(
		http.MethodPost,
		"/mcp/v1/workloads/workload-1",
		bytes.NewReader(body),
	)
	unauthorizedResult := httptest.NewRecorder()
	handler.ServeHTTP(unauthorizedResult, unauthorized)
	if unauthorizedResult.Code != http.StatusUnauthorized {
		t.Fatalf(
			"expected missing Gateway identity to return 401, got %d",
			unauthorizedResult.Code,
		)
	}

	identity, err := url.Parse(expected)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(
		http.MethodPost,
		"/mcp/v1/workloads/workload-1",
		bytes.NewReader(body),
	)
	request.TLS = &tls.ConnectionState{
		VerifiedChains: [][]*x509.Certificate{{
			{URIs: []*url.URL{identity}},
		}},
	}
	request.Header.Set("X-SimplePoint-Runtime-Lease-Id", "lease-1")
	request.Header.Set("X-SimplePoint-Runtime-Fencing-Token", "1")
	result := httptest.NewRecorder()
	handler.ServeHTTP(result, request)
	if result.Code != http.StatusOK {
		t.Fatalf("expected Gateway bridge request to return 200, got %d", result.Code)
	}
	if result.Header().Get("Mcp-Session-Id") != "session-1" {
		t.Fatalf("unexpected MCP session header: %q", result.Header())
	}
}

const testRuntimeToken = "test-runtime-token-0123456789"

func testHandler(runtime Runtime) http.Handler {
	return New(
		config.Config{
			InternalToken:      testRuntimeToken,
			RequestTimeout:     10_000_000_000,
			MaxRequestBytes:    64 * 1024,
			MaxMCPMessageBytes: 64 * 1024,
		},
		runtime,
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
}
