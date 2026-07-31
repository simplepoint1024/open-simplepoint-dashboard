package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * SDK-neutral MCP Task descriptor using protocol field names.
 */
public record McpTaskDescriptor(
    String taskId,
    String status,
    String statusMessage,
    String createdAt,
    String lastUpdatedAt,
    long ttl,
    long pollInterval
) {
}
