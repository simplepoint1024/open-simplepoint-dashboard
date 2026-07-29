package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Sanitized HTTP failure returned by the independent MCP Gateway.
 */
public class McpGatewayUpstreamException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final int statusCode;

  /**
   * Creates an upstream failure carrying only the internal HTTP status.
   */
  public McpGatewayUpstreamException(
      final String message,
      final int statusCode,
      final Throwable cause
  ) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }
}
