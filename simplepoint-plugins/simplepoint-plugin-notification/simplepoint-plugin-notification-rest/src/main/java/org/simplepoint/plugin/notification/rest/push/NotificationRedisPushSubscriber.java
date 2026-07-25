package org.simplepoint.plugin.notification.rest.push;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Optional lifecycle-managed Redis subscriber.
 *
 * <p>It starts after the application context is ready, which allows the notification plugin
 * to run both with and without a Redis connection factory.</p>
 */
@Component
@Slf4j
public class NotificationRedisPushSubscriber implements SmartLifecycle {

  private final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider;

  private final NotificationPushDispatcher dispatcher;

  private volatile RedisMessageListenerContainer container;

  private volatile boolean running;

  /**
   * Creates the optional Redis subscriber.
   *
   * @param connectionFactoryProvider optional Redis connection factory
   * @param dispatcher                cross-instance push dispatcher
   */
  public NotificationRedisPushSubscriber(
      final ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
      final NotificationPushDispatcher dispatcher
  ) {
    this.connectionFactoryProvider = connectionFactoryProvider;
    this.dispatcher = dispatcher;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
    if (connectionFactory == null) {
      return;
    }
    RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
    listenerContainer.setConnectionFactory(connectionFactory);
    listenerContainer.addMessageListener(
        (message, pattern) -> dispatcher.receive(
            new String(message.getBody(), StandardCharsets.UTF_8)
        ),
        new ChannelTopic(NotificationPushDispatcher.CHANNEL)
    );
    try {
      listenerContainer.afterPropertiesSet();
      listenerContainer.start();
      container = listenerContainer;
      running = true;
    } catch (RuntimeException ex) {
      log.warn("Notification Redis subscriber could not start; local SSE remains enabled", ex);
      destroy(listenerContainer);
    }
  }

  @Override
  public synchronized void stop() {
    RedisMessageListenerContainer listenerContainer = container;
    container = null;
    running = false;
    if (listenerContainer != null) {
      listenerContainer.stop();
      destroy(listenerContainer);
    }
  }

  @Override
  public void stop(final Runnable callback) {
    stop();
    callback.run();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  private static void destroy(final RedisMessageListenerContainer listenerContainer) {
    try {
      listenerContainer.destroy();
    } catch (Exception ex) {
      log.debug("Notification Redis listener cleanup failed", ex);
    }
  }
}
