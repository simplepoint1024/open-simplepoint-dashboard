package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Authorization-bound, cursor-paginated MCP Task list request.
 */
public record McpPublicationTaskListRequest(
    String publicationCode,
    String subject,
    String clientId,
    String cursor,
    int limit
) {
}
