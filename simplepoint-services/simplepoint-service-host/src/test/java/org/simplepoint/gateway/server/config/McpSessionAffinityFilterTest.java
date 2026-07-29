package org.simplepoint.gateway.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import org.junit.jupiter.api.Test;

class McpSessionAffinityFilterTest {

  @Test
  void replacesLoadBalancedAuthorityAndPreservesMcpRequestTarget() {
    URI mapped = McpSessionAffinityFilter.mappedUri(
        URI.create("lb://mcp-gateway/mcp/demo?cursor=next"),
        "http://10.0.0.8:2890"
    );

    assertEquals(
        URI.create("http://10.0.0.8:2890/mcp/demo?cursor=next"),
        mapped
    );
  }

  @Test
  void storesOnlyTheSelectedGatewayAuthority() {
    assertEquals(
        "http://10.0.0.8:2890",
        McpSessionAffinityFilter.routeBase(
            URI.create("http://10.0.0.8:2890/mcp/demo")
        )
    );
  }

  @Test
  void rejectsUnsafeOrOversizedSessionIdentifiers() {
    assertNull(McpSessionAffinityFilter.normalizeSessionId("bad\nvalue"));
    assertNull(McpSessionAffinityFilter.normalizeSessionId("x".repeat(257)));
    assertEquals(
        "session-1",
        McpSessionAffinityFilter.normalizeSessionId(" session-1 ")
    );
  }
}
