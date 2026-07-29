package org.simplepoint.mcp.gateway.event;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Relays southbound progress notifications to the originating northbound session.
 */
@Component
public class McpProgressRelay {

  private final ConcurrentHashMap<String, ActiveProgress> active =
      new ConcurrentHashMap<>();

  /**
   * Registers progress forwarding when the client supplied a progress token.
   *
   */
  public void register(
      final String operationId,
      final McpSyncServerExchange exchange,
      final Map<String, Object> requestMeta
  ) {
    Object progressToken = requestMeta == null
        ? null : requestMeta.get("progressToken");
    if (operationId == null || progressToken == null) {
      return;
    }
    active.put(operationId, new ActiveProgress(exchange, progressToken));
  }

  /**
   * Stops forwarding progress for one completed operation.
   */
  public void unregister(final String operationId) {
    if (operationId != null) {
      active.remove(operationId);
    }
  }

  /**
   * Handles progress events received from any Gateway replica.
   */
  @EventListener
  public void onClusterEvent(final McpGatewayClusterEvent event) {
    if (event.kind() != McpGatewayClusterEvent.Kind.PROGRESS
        || event.operationId() == null) {
      return;
    }
    ActiveProgress progress = active.get(event.operationId());
    if (progress == null || event.progress() == null) {
      return;
    }
    progress.exchange().progressNotification(new McpSchema.ProgressNotification(
        progress.clientToken(),
        event.progress(),
        event.total(),
        event.message(),
        event.meta() == null ? Map.of() : event.meta()
    ));
  }

  private record ActiveProgress(
      McpSyncServerExchange exchange,
      Object clientToken
  ) {
  }
}
