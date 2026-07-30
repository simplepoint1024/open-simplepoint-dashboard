package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Capability-authorized Skill Workflow Resource request.
 */
public record McpGatewayWorkflowResourceReadRequest(
    McpGatewayResourceReadRequest call,
    String capabilityToken
) {
}
