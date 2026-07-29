package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.Map;

/**
 * Trusted Gateway-to-control-plane publication prompt request.
 */
public record McpPublicationPromptGetRequest(
    String publicationCode,
    String promptName,
    Map<String, Object> arguments,
    String subject,
    String clientId,
    String sessionId,
    String operationId
) {
}
