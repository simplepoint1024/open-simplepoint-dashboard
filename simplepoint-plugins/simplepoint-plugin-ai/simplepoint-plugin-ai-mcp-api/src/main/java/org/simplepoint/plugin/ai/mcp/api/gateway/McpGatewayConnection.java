package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Connection material sent from the AI control plane to the MCP Gateway.
 *
 * <p>The authorization header is an internal transport field. Callers must not log,
 * persist, or return this record to an external client.</p>
 *
 * @param connectionId stable control-plane identifier for the remote server
 * @param endpointUrl endpoint of a Streamable HTTP MCP server
 * @param authorizationHeader optional complete HTTP Authorization header
 * @param allowPrivateNetwork whether the destination may resolve to a private address
 * @param kind trusted connection boundary
 * @param runtimeLeaseId managed workload lease ID
 * @param runtimeFencingToken managed workload fencing token
 */
public record McpGatewayConnection(
    String connectionId,
    String endpointUrl,
    String authorizationHeader,
    boolean allowPrivateNetwork,
    McpGatewayConnectionKind kind,
    String runtimeLeaseId,
    long runtimeFencingToken
) {

  /**
   * Creates a normal remote Streamable HTTP connection.
   */
  public McpGatewayConnection(
      final String connectionId,
      final String endpointUrl,
      final String authorizationHeader,
      final boolean allowPrivateNetwork
  ) {
    this(
        connectionId,
        endpointUrl,
        authorizationHeader,
        allowPrivateNetwork,
        McpGatewayConnectionKind.REMOTE_HTTP,
        null,
        0
    );
  }
}
