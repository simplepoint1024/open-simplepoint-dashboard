package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Capability-authorized Skill Workflow Prompt request.
 */
public record McpGatewayWorkflowPromptGetRequest(
    McpGatewayPromptGetRequest call,
    String capabilityToken
) {
}
