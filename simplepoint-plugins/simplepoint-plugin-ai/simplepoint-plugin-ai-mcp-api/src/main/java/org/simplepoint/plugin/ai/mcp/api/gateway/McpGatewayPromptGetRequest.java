package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.Map;

/**
 * Internal request to render one remote MCP prompt.
 *
 * @param connection remote MCP connection
 * @param name exact prompt name
 * @param arguments prompt arguments
 * @param meta protocol request metadata, including an optional progress token
 * @param operationId cluster-wide invocation identifier used for cancellation
 */
public record McpGatewayPromptGetRequest(
    McpGatewayConnection connection,
    String name,
    Map<String, Object> arguments,
    Map<String, Object> meta,
    String operationId
) {
}
