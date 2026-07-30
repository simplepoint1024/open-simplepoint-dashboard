package org.simplepoint.plugin.ai.mcp.api.gateway;

/**
 * Dedicated internal request for one capability-authorized workflow Tool call.
 *
 * @param call exact MCP Tool invocation
 * @param capabilityToken short-lived, single-use Skill capability
 */
public record McpGatewayWorkflowToolCallRequest(
    McpGatewayToolCallRequest call,
    String capabilityToken
) {
}
