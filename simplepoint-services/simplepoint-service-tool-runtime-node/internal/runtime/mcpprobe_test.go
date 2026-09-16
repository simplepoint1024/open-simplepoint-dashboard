package runtime

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestRetryProbeInitializeAllowsSlowStdioStartup(t *testing.T) {
	t.Parallel()
	attempts := 0
	want := probeResponse{Result: []byte(`{"protocolVersion":"2025-11-25"}`)}
	response, sessionID, err := retryProbeInitialize(
		context.Background(),
		time.Second,
		time.Millisecond,
		func() (probeResponse, string, error) {
			attempts++
			if attempts < 3 {
				return probeResponse{}, "", errors.New("runtime workload is not running")
			}
			return want, "stdio-session", nil
		},
	)
	if err != nil {
		t.Fatalf("retry probe initialize: %v", err)
	}
	if attempts != 3 || sessionID != "stdio-session" ||
		string(response.Result) != string(want.Result) {
		t.Fatalf(
			"unexpected retry result: attempts=%d session=%q response=%s",
			attempts,
			sessionID,
			response.Result,
		)
	}
}

func TestRetryProbeInitializeStopsWhenContextIsCancelled(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	attempts := 0
	_, _, err := retryProbeInitialize(
		ctx,
		time.Second,
		time.Second,
		func() (probeResponse, string, error) {
			attempts++
			return probeResponse{}, "", errors.New("not ready")
		},
	)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context cancellation, got %v", err)
	}
	if attempts != 1 {
		t.Fatalf("expected one attempt before cancellation, got %d", attempts)
	}
}
