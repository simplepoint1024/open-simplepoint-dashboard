package org.simplepoint.plugin.ai.mcp.api.gateway;

import java.util.List;
import java.util.Set;

/**
 * Validated publication changes returned by the AI control plane.
 *
 * @param publicationCodes active publications backed by the changed server
 * @param eventTypes event types to broadcast to Gateway replicas
 * @param resourceUris resource URIs affected by update notifications
 */
public record McpPublicationChangeEvent(
    List<String> publicationCodes,
    Set<McpGatewayEventType> eventTypes,
    List<String> resourceUris
) {
}
