package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Set;

/**
 * Trusted Gateway event sent to the AI control plane.
 *
 * @param connectionId upstream MCP server identifier
 * @param eventTypes event types coalesced by the Gateway
 * @param resourceUris resource URIs affected by update notifications
 * @param discovery complete discovery result for list changes
 */
public record McpGatewayUpstreamEvent(
    String connectionId,
    Set<McpGatewayEventType> eventTypes,
    List<String> resourceUris,
    McpGatewayDiscoveryResult discovery
) {
}
