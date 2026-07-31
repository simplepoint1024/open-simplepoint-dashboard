package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.Map;

/**
 * Trusted Gateway request that creates one durable MCP Tool task.
 */
public record McpPublicationTaskCreateRequest(
    String publicationCode,
    String toolName,
    Map<String, Object> arguments,
    String subject,
    String clientId,
    String sessionId,
    long ttl
) {
}
