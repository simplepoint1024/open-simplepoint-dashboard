package runtime

import (
	"strings"
	"testing"
)

func TestScanMCPEventStreamForwardsOnlyValidJSONRPCData(t *testing.T) {
	messages := make(chan []byte, 4)
	scanMCPEventStream(
		strings.NewReader(
			": keepalive\n\n"+
				"event: message\n"+
				"data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n"+
				"data: not-json\n\n",
		),
		64*1024,
		messages,
	)
	select {
	case message := <-messages:
		if !strings.Contains(string(message), "tools/list_changed") {
			t.Fatalf("unexpected MCP event: %s", message)
		}
	default:
		t.Fatal("expected one valid MCP event")
	}
	select {
	case message := <-messages:
		t.Fatalf("unexpected invalid MCP event: %s", message)
	default:
	}
}

func TestValidTransportPathRejectsTraversalAndQuery(t *testing.T) {
	for _, value := range []string{
		"mcp", "//mcp", "/../mcp", "/%2e%2e/mcp", "/mcp?token=x",
		"/mcp#fragment", "/mcp\\child", "/mcp\nchild",
	} {
		if validTransportPath(value) {
			t.Fatalf("expected transport path %q to be rejected", value)
		}
	}
	if !validTransportPath("/mcp/v1") {
		t.Fatal("expected a normal MCP path to be accepted")
	}
}
