package org.simplepoint.mcp.gateway.publication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.simplepoint.mcp.gateway.config.McpGatewayProperties;
import org.simplepoint.mcp.gateway.event.McpCancellationRegistry;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpPublicationDispatcherServletTest {

  @Test
  void interceptsStandardCancellationBeforeTheSdkTransport() throws Exception {
    McpPublicationRegistry registry = mock(McpPublicationRegistry.class);
    McpCancellationRegistry cancellationRegistry =
        mock(McpCancellationRegistry.class);
    McpPublicationControlPlaneClient controlPlaneClient =
        mock(McpPublicationControlPlaneClient.class);
    final McpPublicationDispatcherServlet servlet =
        new McpPublicationDispatcherServlet(
            registry,
            cancellationRegistry,
            controlPlaneClient,
            new ObjectMapper(),
            new McpGatewayProperties()
        );
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/mcp/demo"
    );
    request.setPathInfo("/demo");
    request.addHeader("Mcp-Session-Id", "session-1");
    request.setContentType("application/json");
    request.setContent("""
        {
          "jsonrpc": "2.0",
          "method": "notifications/cancelled",
          "params": {"requestId": 17, "reason": "Client stopped"}
        }
        """.getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();

    servlet.service(request, response);

    assertEquals(202, response.getStatus());
    verify(cancellationRegistry).cancel(
        "demo",
        "session-1",
        "n:17",
        "Client stopped"
    );
    verify(registry, never()).transport("demo");
  }
}
