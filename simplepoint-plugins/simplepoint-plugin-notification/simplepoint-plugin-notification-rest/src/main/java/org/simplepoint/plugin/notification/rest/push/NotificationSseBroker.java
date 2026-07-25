package org.simplepoint.plugin.notification.rest.push;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.AudienceContext;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.NotificationChangedEvent;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.PushEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process SSE fan-out broker.
 *
 * <p>The durable database inbox remains the source of truth. Push events only prompt clients
 * to reconcile, so reconnects and duplicate events are safe.</p>
 */
@Component
public class NotificationSseBroker {

  private static final long CONNECTION_TIMEOUT_MILLIS = 30L * 60L * 1000L;

  private static final int MAX_CONNECTIONS_PER_USER = 5;

  private final Map<String, Client> clients = new ConcurrentHashMap<>();

  /**
   * Opens one authenticated audience-aware SSE connection.
   *
   * @param audience immutable request authorization scope
   * @return connected emitter
   */
  public synchronized SseEmitter connect(final AudienceContext audience) {
    while (connectionCount(audience.userId()) >= MAX_CONNECTIONS_PER_USER) {
      clients.entrySet().stream()
          .filter(entry -> entry.getValue().audience().userId().equals(audience.userId()))
          .min(Comparator.comparing(entry -> entry.getValue().connectedAt()))
          .ifPresent(entry -> {
            Client removed = clients.remove(entry.getKey());
            if (removed != null) {
              removed.emitter().complete();
            }
          });
    }
    String clientId = UUID.randomUUID().toString();
    SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MILLIS);
    clients.put(clientId, new Client(audience, emitter, Instant.now()));
    emitter.onCompletion(() -> clients.remove(clientId));
    emitter.onTimeout(() -> clients.remove(clientId));
    emitter.onError(error -> clients.remove(clientId));
    send(clientId, "ready", Map.of("connectedAt", Instant.now()));
    return emitter;
  }

  /** Delivers a committed notification state change to matching local connections. */
  public void broadcast(final NotificationChangedEvent event) {
    PushEvent payload = new PushEvent(
        event.type(),
        event.notificationId(),
        event.occurredAt()
    );
    clients.forEach((clientId, client) -> {
      if (matches(client.audience(), event)) {
        send(clientId, "notification", payload);
      }
    });
  }

  /** Keeps reverse proxies and browsers from treating idle notification streams as dead. */
  @Scheduled(fixedDelay = 25_000L)
  public void heartbeat() {
    clients.keySet().forEach(clientId ->
        send(clientId, "heartbeat", Map.of("at", Instant.now()))
    );
  }

  private static boolean matches(
      final AudienceContext audience,
      final NotificationChangedEvent event
  ) {
    NotificationAudienceType audienceType = event.audienceType();
    if (audienceType == NotificationAudienceType.ALL) {
      return true;
    }
    if (audienceType == NotificationAudienceType.PLATFORM) {
      return audience.scopeType() == org.simplepoint.core.AuthorizationScopeType.PLATFORM;
    }
    if (audienceType == NotificationAudienceType.TENANT) {
      return audience.scopeType() == org.simplepoint.core.AuthorizationScopeType.TENANT
          && event.audienceId() != null
          && event.audienceId().equals(audience.tenantId());
    }
    return audienceType == NotificationAudienceType.USER
        && event.audienceId() != null
        && event.audienceId().equals(audience.userId());
  }

  private void send(
      final String clientId,
      final String eventName,
      final Object data
  ) {
    Client client = clients.get(clientId);
    if (client == null) {
      return;
    }
    try {
      client.emitter().send(SseEmitter.event().name(eventName).data(data));
    } catch (IOException | IllegalStateException ex) {
      clients.remove(clientId);
      client.emitter().complete();
    }
  }

  private long connectionCount(final String userId) {
    return clients.values().stream()
        .filter(client -> client.audience().userId().equals(userId))
        .count();
  }

  private record Client(
      AudienceContext audience,
      SseEmitter emitter,
      Instant connectedAt
  ) {
  }
}
