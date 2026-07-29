package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Protocol-neutral contract implemented by the independent MCP Gateway.
 */
public interface McpGatewayOperations {

  /**
   * Returns status and protocol compatibility information for this Gateway instance.
   *
   * @return gateway status
   */
  McpGatewayStatus status();

  /**
   * Initializes a remote MCP server and returns its current capabilities.
   *
   * @param connection connection material
   * @return discovered capabilities
   */
  McpGatewayDiscoveryResult discover(McpGatewayConnection connection);

  /**
   * Calls one tool on a remote MCP server.
   *
   * @param request invocation request
   * @return invocation result
   */
  McpGatewayToolCallResult callTool(McpGatewayToolCallRequest request);

  /**
   * Reads one remote MCP resource.
   */
  McpGatewayResourceReadResult readResource(McpGatewayResourceReadRequest request);

  /**
   * Renders one remote MCP prompt.
   */
  McpGatewayPromptGetResult getPrompt(McpGatewayPromptGetRequest request);

  /**
   * Discovers protected-resource and authorization-server metadata.
   *
   * @param request OAuth discovery request
   * @return validated metadata
   */
  McpGatewayOauthDiscoveryResult discoverOauth(McpGatewayOauthDiscoveryRequest request);

  /**
   * Registers a client with a discovered RFC 7591 endpoint.
   *
   * @param request registration request
   * @return registered client credentials
   */
  McpGatewayOauthRegistrationResult registerOauthClient(
      McpGatewayOauthRegistrationRequest request
  );

  /**
   * Exchanges an authorization code or refresh token.
   *
   * @param request token request
   * @return token response
   */
  McpGatewayOauthTokenResult exchangeOauthToken(McpGatewayOauthTokenRequest request);
}
