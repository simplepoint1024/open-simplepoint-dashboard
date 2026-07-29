package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.Map;

/**
 * Internal request to read one remote MCP resource.
 *
 * @param connection remote MCP connection
 * @param uri exact resource URI
 * @param meta protocol request metadata, including an optional progress token
 * @param operationId cluster-wide invocation identifier used for cancellation
 */
public record McpGatewayResourceReadRequest(
    McpGatewayConnection connection,
    String uri,
    Map<String, Object> meta,
    String operationId
) {
}
