package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.Map;

/**
 * Trusted Gateway-to-control-plane publication tool call.
 */
public record McpPublicationToolCallRequest(
    String publicationCode,
    String toolName,
    Map<String, Object> arguments,
    String subject,
    String clientId,
    String sessionId,
    String operationId
) {
}
