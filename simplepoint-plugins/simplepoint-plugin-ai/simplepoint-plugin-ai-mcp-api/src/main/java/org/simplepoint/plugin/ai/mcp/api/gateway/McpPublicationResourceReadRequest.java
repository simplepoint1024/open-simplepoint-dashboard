package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Trusted Gateway-to-control-plane publication resource read.
 */
public record McpPublicationResourceReadRequest(
    String publicationCode,
    String uri,
    String subject,
    String clientId,
    String sessionId,
    String operationId
) {
}
