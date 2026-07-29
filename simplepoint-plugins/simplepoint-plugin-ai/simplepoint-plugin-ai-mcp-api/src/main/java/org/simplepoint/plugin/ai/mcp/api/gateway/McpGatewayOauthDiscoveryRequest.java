package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Requests RFC 9728 and authorization-server metadata discovery.
 *
 * @param endpointUrl canonical remote MCP endpoint
 * @param allowPrivateNetwork whether private destinations may be accessed
 */
public record McpGatewayOauthDiscoveryRequest(
    String endpointUrl,
    boolean allowPrivateNetwork
) {
}
