package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Authorization-bound request for one existing MCP Task.
 */
public record McpPublicationTaskRequest(
    String publicationCode,
    String taskId,
    String subject,
    String clientId
) {
}
