package org.simplepoint.plugin.notification.rest.push;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationChangeType;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.NotificationChangedEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class NotificationPushDispatcherTest {

  private final ObjectMapper objectMapper =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final NotificationSseBroker broker = mock(NotificationSseBroker.class);

  @Test
  void fallsBackToLocalBrokerWithoutRedis() {
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    NotificationPushDispatcher dispatcher =
        new NotificationPushDispatcher(objectMapper, provider, broker);
    NotificationChangedEvent event = event();

    dispatcher.dispatch(event);

    verify(broker).broadcast(event);
  }

  @Test
  void redisFanOutAvoidsDuplicateLocalDelivery() {
    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    when(provider.getIfAvailable()).thenReturn(redisTemplate);
    when(redisTemplate.convertAndSend(
        NotificationPushDispatcher.CHANNEL,
        objectMapperString(event())
    )).thenReturn(2L);
    NotificationPushDispatcher dispatcher =
        new NotificationPushDispatcher(objectMapper, provider, broker);

    dispatcher.dispatch(event());

    verify(broker, never()).broadcast(event());
  }

  private String objectMapperString(final NotificationChangedEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static NotificationChangedEvent event() {
    return new NotificationChangedEvent(
        NotificationChangeType.PUBLISHED,
        "notification-1",
        NotificationAudienceType.ALL,
        null,
        Instant.parse("2026-07-25T00:00:00Z")
    );
  }
}
