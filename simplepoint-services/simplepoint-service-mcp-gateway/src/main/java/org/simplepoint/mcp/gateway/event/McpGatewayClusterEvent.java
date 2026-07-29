package org.simplepoint.mcp.gateway.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayEventType;

/**
 * Redis-broadcast event consumed by every MCP Gateway replica.
 */
public record McpGatewayClusterEvent(
    Kind kind,
    List<String> publicationCodes,
    Set<McpGatewayEventType> eventTypes,
    List<String> resourceUris,
    String operationId,
    Double progress,
    Double total,
    String message,
    Map<String, Object> meta,
    Instant occurredAt
) {

  /**
   * Cluster event category.
   */
  public enum Kind {
    PUBLICATION_CHANGE,
    PROGRESS,
    CANCELLATION
  }
}
