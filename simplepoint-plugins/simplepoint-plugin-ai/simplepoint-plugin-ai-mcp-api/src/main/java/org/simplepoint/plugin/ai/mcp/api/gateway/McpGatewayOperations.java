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
   * Cooperatively cancels one cluster-wide in-flight operation.
   */
  default void cancel(final McpGatewayCancellationRequest request) {
    // In-process test adapters may not have a cancellable transport.
  }

  /**
   * Calls one workflow-bound Tool through the Capability Token boundary.
   *
   * <p>Gateway implementations may override this method to use a dedicated
   * internal endpoint. In-process protocol implementations keep the normal Tool
   * call behavior; the HTTP boundary performs token validation.</p>
   *
   * @param request capability-authorized invocation
   * @return invocation result
   */
  default McpGatewayToolCallResult callWorkflowTool(
      final McpGatewayWorkflowToolCallRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "MCP workflow Tool request must not be null"
      );
    }
    return callTool(request.call());
  }

  /**
   * Renders one workflow-bound Prompt through the Capability Token boundary.
   */
  default McpGatewayPromptGetResult getWorkflowPrompt(
      final McpGatewayWorkflowPromptGetRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "MCP workflow Prompt request must not be null"
      );
    }
    return getPrompt(request.call());
  }

  /**
   * Reads one workflow-bound Resource through the Capability Token boundary.
   */
  default McpGatewayResourceReadResult readWorkflowResource(
      final McpGatewayWorkflowResourceReadRequest request
  ) {
    if (request == null) {
      throw new IllegalArgumentException(
          "MCP workflow Resource request must not be null"
      );
    }
    return readResource(request.call());
  }

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
