package controlplane

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	runtimeapi "github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/runtime"
)

type staticStatusProvider struct {
	status runtimeapi.NodeStatus
}

func (p staticStatusProvider) NodeStatus(
	context.Context,
) (runtimeapi.NodeStatus, error) {
	return p.status, nil
}

func TestRunMaintainsRegistrationAndReportsOffline(t *testing.T) {
	var registrations atomic.Int32
	var heartbeats atomic.Int32
	var offline atomic.Int32
	var instanceID atomic.Value
	heartbeatSeen := make(chan struct{}, 1)

	server := httptest.NewServer(http.HandlerFunc(func(
		response http.ResponseWriter,
		request *http.Request,
	) {
		if request.Header.Get(controlPlaneHeader) != "control-plane-token-0123456789" {
			t.Error("expected control-plane authentication header")
		}
		if request.Header.Get("Content-Type") != "application/json" {
			t.Error("expected JSON content type")
		}
		switch request.URL.Path {
		case "/internal/runtime/nodes/node-a/registration":
			var body registrationRequest
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
				t.Errorf("decode registration: %v", err)
				return
			}
			if body.AdvertiseURL != "http://runtime-a:2891" ||
				body.CPUCores != 8 ||
				body.MemoryBytes != 16*1024*1024*1024 ||
				body.Labels["zone"] != "test-a" {
				t.Errorf("unexpected registration payload: %#v", body)
			}
			instanceID.Store(body.InstanceID)
			registrations.Add(1)
			writeControlResponse(response, body.InstanceID)
		case "/internal/runtime/nodes/node-a/heartbeat":
			var body heartbeatRequest
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
				t.Errorf("decode heartbeat: %v", err)
				return
			}
			if !body.Healthy || body.RunningWorkloads != 2 {
				t.Errorf("unexpected heartbeat payload: %#v", body)
			}
			heartbeats.Add(1)
			heartbeatSeen <- struct{}{}
			writeControlResponse(response, body.InstanceID)
		case "/internal/runtime/nodes/node-a/offline":
			var body offlineRequest
			if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
				t.Errorf("decode offline request: %v", err)
				return
			}
			if expected, _ := instanceID.Load().(string); body.InstanceID != expected {
				t.Errorf("unexpected offline instance ID: %q", body.InstanceID)
			}
			offline.Add(1)
			writeControlResponse(response, body.InstanceID)
		default:
			http.NotFound(response, request)
		}
	}))
	defer server.Close()

	cfg := testConfig(server.URL)
	provider := staticStatusProvider{status: runtimeapi.NodeStatus{
		NodeID:                 "node-a",
		DisplayName:            "docker-a",
		Status:                 "UP",
		EngineAPIVersion:       "1.51",
		EngineOSType:           "linux",
		CPUCores:               8,
		MemoryBytes:            16 * 1024 * 1024 * 1024,
		MaxWorkloads:           32,
		RunningWorkloads:       2,
		MaxWorkloadMemoryBytes: 2 * 1024 * 1024 * 1024,
		MaxWorkloadNanoCPUs:    4_000_000_000,
		MaxWorkloadPidsLimit:   512,
	}}
	client, err := New(
		cfg,
		"test-version",
		provider,
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	if err != nil {
		t.Fatalf("create client: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		client.Run(ctx)
	}()
	select {
	case <-heartbeatSeen:
		cancel()
	case <-time.After(3 * time.Second):
		cancel()
		t.Fatal("timed out waiting for heartbeat")
	}
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for control-plane shutdown")
	}

	if registrations.Load() != 1 {
		t.Fatalf("expected one registration, got %d", registrations.Load())
	}
	if heartbeats.Load() != 1 {
		t.Fatalf("expected one heartbeat, got %d", heartbeats.Load())
	}
	if offline.Load() != 1 {
		t.Fatalf("expected one offline notification, got %d", offline.Load())
	}
}

func TestPostReturnsTypedStatusWithoutFollowingRedirects(t *testing.T) {
	server := httptest.NewServer(http.RedirectHandler(
		"https://untrusted.example.test",
		http.StatusTemporaryRedirect,
	))
	defer server.Close()
	cfg := testConfig(server.URL)
	client, err := New(
		cfg,
		"test-version",
		staticStatusProvider{},
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	if err != nil {
		t.Fatalf("create client: %v", err)
	}

	var response controlResponse
	err = client.post(
		context.Background(),
		"heartbeat",
		heartbeatRequest{},
		&response,
	)
	statusErr, ok := err.(responseError)
	if !ok || statusErr.StatusCode != http.StatusTemporaryRedirect {
		t.Fatalf("expected typed redirect status, got %v", err)
	}
}

func TestRegistrationRequestUsesJavaCompatibleAppArmorProperty(t *testing.T) {
	payload, err := json.Marshal(registrationRequest{
		AppArmorEnforced: true,
		AppArmorProfile:  "open-simplepoint-mcp-workload",
	})
	if err != nil {
		t.Fatalf("marshal registration request: %v", err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(payload, &fields); err != nil {
		t.Fatalf("decode registration request: %v", err)
	}
	if _, ok := fields["appArmorEnforced"]; !ok {
		t.Fatalf("missing Java-compatible appArmorEnforced property: %s", payload)
	}
	if _, ok := fields["appArmorProfile"]; !ok {
		t.Fatalf("missing Java-compatible appArmorProfile property: %s", payload)
	}
	if _, ok := fields["apparmorEnforced"]; ok {
		t.Fatalf("unexpected legacy apparmorEnforced property: %s", payload)
	}
}

func testConfig(controlPlaneURL string) config.Config {
	return config.Config{
		NodeID:            "node-a",
		ControlPlaneURL:   controlPlaneURL,
		ControlPlaneToken: "control-plane-token-0123456789",
		AdvertiseURL:      "http://runtime-a:2891",
		NodeLabels: map[string]string{
			"zone": "test-a",
		},
		MaxWorkloads:      32,
		MaxMemoryBytes:    2 * 1024 * 1024 * 1024,
		MaxNanoCPUs:       4_000_000_000,
		MaxPidsLimit:      512,
		RegistrationRetry: 10 * time.Millisecond,
		RequestTimeout:    time.Second,
		ShutdownTimeout:   time.Second,
	}
}

func writeControlResponse(response http.ResponseWriter, instanceID string) {
	response.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(response).Encode(controlResponse{
		NodeID:                   "node-a",
		InstanceID:               instanceID,
		Generation:               1,
		Status:                   "READY",
		AcceptedAt:               time.Now().UTC(),
		HeartbeatIntervalSeconds: 1,
		HeartbeatTimeoutSeconds:  5,
	})
}
