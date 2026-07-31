package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Trusted request to cooperatively cancel one cluster-wide MCP operation.
 */
public record McpGatewayCancellationRequest(
    String operationId,
    String reason
) {
}
