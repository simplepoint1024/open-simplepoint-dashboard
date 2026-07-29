package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.time.Instant;

/**
 * Status of one independently deployed MCP Gateway instance.
 *
 * @param service service identifier
 * @param status operational status
 * @param protocolBaseline supported stable MCP protocol baseline
 * @param sdkVersion official MCP Java SDK version
 * @param instanceId runtime instance identifier
 * @param checkedAt status generation time
 */
public record McpGatewayStatus(
    String service,
    String status,
    String protocolBaseline,
    String sdkVersion,
    String instanceId,
    Instant checkedAt
) {
}
