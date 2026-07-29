package org.simplepoint.mcp.gateway.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class McpGatewayExceptionHandlerTest {

  @Test
  void status_preservesIntentionalEndpointStatus() {
    McpGatewayExceptionHandler handler = new McpGatewayExceptionHandler();

    ResponseEntity<Map<String, Object>> response = handler.status(
        new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MCP publication metadata is unavailable"
        )
    );

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals(
        "MCP publication metadata is unavailable",
        response.getBody().get("message")
    );
  }
}
