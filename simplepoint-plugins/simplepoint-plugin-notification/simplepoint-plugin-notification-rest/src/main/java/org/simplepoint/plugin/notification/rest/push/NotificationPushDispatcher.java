package org.simplepoint.plugin.notification.rest.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.NotificationChangedEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Routes committed notification events through Redis when available.
 *
 * <p>Redis Pub/Sub provides cross-instance fan-out. The database inbox remains durable, and
 * the local broker is used as a fallback if Redis is absent or temporarily unavailable.</p>
 */
@Component
@Slf4j
public class NotificationPushDispatcher {

  /** Cross-instance notification change channel. */
  public static final String CHANNEL = "simplepoint:notifications:changed";

  private final ObjectMapper objectMapper;

  private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

  private final NotificationSseBroker sseBroker;

  /**
   * Creates the cross-instance push dispatcher.
   *
   * @param objectMapper          JSON mapper
   * @param redisTemplateProvider optional Redis publisher
   * @param sseBroker             local SSE fan-out broker
   */
  public NotificationPushDispatcher(
      final ObjectMapper objectMapper,
      final ObjectProvider<StringRedisTemplate> redisTemplateProvider,
      final NotificationSseBroker sseBroker
  ) {
    this.objectMapper = objectMapper;
    this.redisTemplateProvider = redisTemplateProvider;
    this.sseBroker = sseBroker;
  }

  /** Publishes only after the database transaction has committed. */
  @TransactionalEventListener(
      phase = TransactionPhase.AFTER_COMMIT,
      fallbackExecution = true
  )
  public void dispatch(final NotificationChangedEvent event) {
    StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
    if (redisTemplate != null) {
      try {
        Long subscribers = redisTemplate.convertAndSend(
            CHANNEL,
            objectMapper.writeValueAsString(event)
        );
        if (subscribers != null && subscribers > 0L) {
          return;
        }
      } catch (RuntimeException | JsonProcessingException ex) {
        log.warn("Notification Redis fan-out failed; using local SSE delivery", ex);
      }
    }
    sseBroker.broadcast(event);
  }

  /** Receives a cross-instance Redis message and fans it out to local SSE clients. */
  public void receive(final String payload) {
    try {
      sseBroker.broadcast(objectMapper.readValue(payload, NotificationChangedEvent.class));
    } catch (JsonProcessingException ex) {
      log.warn("Ignoring malformed notification message from Redis", ex);
    }
  }
}
