package runtime

import (
	"testing"
)

func TestMCPSessionTakeoverRequiresDisconnectedEventStream(t *testing.T) {
	session := testMCPSession()
	if session.takeoverAllowed() {
		t.Fatal("session without an observed event stream must not be replaceable")
	}

	release, ok := session.acquireEventStream()
	if !ok {
		t.Fatal("expected event stream acquisition to succeed")
	}
	if session.takeoverAllowed() {
		t.Fatal("session with an active event stream must not be replaceable")
	}

	release()
	if !session.takeoverAllowed() {
		t.Fatal("disconnected event stream should allow session takeover")
	}
}

func TestMCPSessionTakeoverWaitsForPendingRequest(t *testing.T) {
	session := testMCPSession()
	release, ok := session.acquireEventStream()
	if !ok {
		t.Fatal("expected event stream acquisition to succeed")
	}
	release()

	session.pending["1"] = make(chan []byte, 1)
	if session.takeoverAllowed() {
		t.Fatal("session with a pending request must not be replaceable")
	}
	delete(session.pending, "1")
	if !session.takeoverAllowed() {
		t.Fatal("completed request should allow orphaned session takeover")
	}
}

func TestMCPEventStreamReleaseIsIdempotent(t *testing.T) {
	session := testMCPSession()
	first, ok := session.acquireEventStream()
	if !ok {
		t.Fatal("expected first event stream acquisition to succeed")
	}
	second, ok := session.acquireEventStream()
	if !ok {
		t.Fatal("expected second event stream acquisition to succeed")
	}

	first()
	first()
	if session.takeoverAllowed() {
		t.Fatal("remaining event stream must prevent takeover")
	}
	second()
	if !session.takeoverAllowed() {
		t.Fatal("all released event streams should allow takeover")
	}
}

func testMCPSession() *mcpSession {
	return &mcpSession{
		pending: make(map[string]chan []byte),
		events:  make(chan []byte, 1),
		done:    make(chan struct{}),
	}
}
