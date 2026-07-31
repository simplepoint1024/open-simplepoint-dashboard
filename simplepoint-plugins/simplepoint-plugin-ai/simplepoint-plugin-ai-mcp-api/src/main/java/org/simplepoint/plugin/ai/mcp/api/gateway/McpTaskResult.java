package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Durable task state plus the exact original Tool result when terminal.
 */
public record McpTaskResult(
    McpTaskDescriptor task,
    McpGatewayToolCallResult result,
    String errorCode,
    String errorMessage
) {
}
