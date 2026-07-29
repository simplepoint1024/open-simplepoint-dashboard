package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.Map;

/**
 * Internal request to invoke one tool through MCP Gateway.
 *
 * @param connection remote MCP connection
 * @param toolName exact tool name discovered from the server
 * @param arguments tool arguments
 * @param meta protocol request metadata, including an optional progress token
 * @param operationId cluster-wide invocation identifier used for cancellation
 */
public record McpGatewayToolCallRequest(
    McpGatewayConnection connection,
    String toolName,
    Map<String, Object> arguments,
    Map<String, Object> meta,
    String operationId
) {
}
