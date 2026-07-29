package org.simplepoint.mcp.gateway.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class McpEndpointPolicyTest {

  private final McpEndpointPolicy policy = new McpEndpointPolicy();

  @Test
  void separatesBaseUriAndEndpointPath() {
    McpEndpointPolicy.Endpoint endpoint = policy.validate(
        "https://mcp.example.com:8443/custom/mcp",
        true
    );

    assertEquals("https://mcp.example.com:8443", endpoint.baseUri());
    assertEquals("/custom/mcp", endpoint.endpointPath());
  }

  @Test
  void rejectsCredentialsQueryAndFragment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.validate("https://user@example.com/mcp", false)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.validate("https://example.com/mcp?token=secret", false)
    );
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.validate("https://example.com/mcp#fragment", false)
    );
  }

  @Test
  void rejectsPrivateDestinationUnlessExplicitlyAllowed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.validate("http://127.0.0.1:8080/mcp", false)
    );

    McpEndpointPolicy.Endpoint endpoint = policy.validate(
        "http://127.0.0.1:8080/mcp",
        true
    );
    assertEquals("http://127.0.0.1:8080", endpoint.baseUri());
  }
}
