package org.simplepoint.mcp.gateway.event;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Correlates standard northbound cancellation notifications with active calls.
 */
@Component
public class McpCancellationRegistry {

  public static final String REQUEST_ID_CONTEXT_KEY = "mcpRequestId";

  private final ConcurrentHashMap<RequestKey, ActiveOperation> northbound =
      new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, Thread> southbound =
      new ConcurrentHashMap<>();

  private final McpGatewayClusterEventBus eventBus;

  /**
   * Creates the cancellation registry.
   */
  public McpCancellationRegistry(final McpGatewayClusterEventBus eventBus) {
    this.eventBus = eventBus;
  }

  /**
   * Registers one northbound request and returns its cluster operation ID.
   */
  public String beginNorthbound(
      final String publicationCode,
      final McpSyncServerExchange exchange
  ) {
    String operationId = UUID.randomUUID().toString();
    String requestId = context(exchange, REQUEST_ID_CONTEXT_KEY);
    if (requestId != null) {
      northbound.put(
          new RequestKey(publicationCode, exchange.sessionId(), requestId),
          new ActiveOperation(operationId, Thread.currentThread())
      );
    }
    return operationId;
  }

  /**
   * Removes one completed northbound operation.
   */
  public void completeNorthbound(
      final String publicationCode,
      final McpSyncServerExchange exchange,
      final String operationId
  ) {
    String requestId = context(exchange, REQUEST_ID_CONTEXT_KEY);
    if (requestId != null) {
      northbound.remove(
          new RequestKey(publicationCode, exchange.sessionId(), requestId),
          new ActiveOperation(operationId, Thread.currentThread())
      );
    }
  }

  /**
   * Cancels one request identified by the standard JSON-RPC request ID.
   */
  public void cancel(
      final String publicationCode,
      final String sessionId,
      final String requestId,
      final String reason
  ) {
    if (requestId == null) {
      return;
    }
    ActiveOperation active = northbound.remove(
        new RequestKey(publicationCode, sessionId, requestId)
    );
    if (active == null) {
      return;
    }
    active.thread().interrupt();
    eventBus.publish(new McpGatewayClusterEvent(
        McpGatewayClusterEvent.Kind.CANCELLATION,
        List.of(publicationCode),
        Set.of(),
        List.of(),
        active.operationId(),
        null,
        null,
        truncate(reason),
        Map.of(),
        Instant.now()
    ));
  }

  /**
   * Registers the thread currently blocking on a southbound MCP request.
   */
  public void beginSouthbound(final String operationId) {
    if (operationId != null && !operationId.isBlank()) {
      southbound.put(operationId, Thread.currentThread());
    }
  }

  /**
   * Removes one completed southbound request.
   */
  public void completeSouthbound(final String operationId) {
    if (operationId != null) {
      southbound.remove(operationId, Thread.currentThread());
    }
  }

  /**
   * Applies a cancellation event on the replica holding the remote call.
   */
  @EventListener
  public void onClusterEvent(final McpGatewayClusterEvent event) {
    if (event.kind() != McpGatewayClusterEvent.Kind.CANCELLATION
        || event.operationId() == null) {
      return;
    }
    Thread thread = southbound.remove(event.operationId());
    if (thread != null) {
      thread.interrupt();
    }
  }

  private static String context(
      final McpSyncServerExchange exchange,
      final String key
  ) {
    Object value = exchange.transportContext().get(key);
    if (value == null || value.toString().isBlank()) {
      return null;
    }
    return value.toString();
  }

  private static String truncate(final String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 512 ? value : value.substring(0, 512);
  }

  private record RequestKey(
      String publicationCode,
      String sessionId,
      String requestId
  ) {
  }

  private record ActiveOperation(String operationId, Thread thread) {
  }
}
