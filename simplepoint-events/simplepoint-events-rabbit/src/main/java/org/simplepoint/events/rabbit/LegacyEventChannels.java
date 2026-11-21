package org.simplepoint.events.rabbit;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.LegacyEventOwner;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventDispatcher;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;

/** Hosts isolated logical owners on legacy, single-storage processes. */
final class LegacyEventChannels implements DisposableBean {
  private final Map<String, Channel> channels = new LinkedHashMap<>();
  private final EventCodec codec;
  private final EventInfrastructureProperties properties;

  LegacyEventChannels(DataSource source, PlatformTransactionManager manager, EventCodec codec,
      BiFunction<String, String, CachingConnectionFactory> connections,
      EventInfrastructureProperties properties) {
    this.codec = codec;
    this.properties = properties;
    if (properties.isDeclareTopology() && !properties.getExtraOwners().isEmpty()) {
      throw new IllegalStateException("Additional event owners require provisioned topology");
    }
    try {
      properties.getExtraOwners().forEach((name, binding) -> {
        String owner = EventMessage.token(name, "event owner");
        String secret = binding.getPassword();
        if (owner.equals(properties.getService()) || secret == null || secret.isBlank()) {
          throw new IllegalStateException("Invalid additional event owner configuration: " + owner);
        }
        var store = new JdbcEventStore(source, manager, codec, owner, Clock.systemUTC(),
            new EventRetryPolicy(properties.getMaxAttempts(), properties.getLease(),
                properties.getInitialRetryDelay(), properties.getMaxRetryDelay()));
        store.count("INBOX", "PENDING");
        channels.put(owner, new Channel(store, connections.apply(owner, secret)));
      });
    } catch (RuntimeException failure) {
      destroy();
      throw failure;
    }
  }

  EventPublisher publisher(Class<?> recipient, EventPublisher defaults) {
    var owner = recipient.getAnnotation(LegacyEventOwner.class);
    return owner == null ? defaults : require(owner.value()).store();
  }

  static boolean usesDefaultOwner(EventHandler handler) {
    return !AopUtils.getTargetClass(handler).isAnnotationPresent(LegacyEventOwner.class);
  }

  SmartLifecycle runtime(List<EventHandler> handlers) {
    Map<String, List<EventHandler>> owned = new LinkedHashMap<>();
    for (EventHandler handler : handlers) {
      var annotation = AopUtils.getTargetClass(handler).getAnnotation(LegacyEventOwner.class);
      if (annotation == null) continue;
      require(annotation.value());
      owned.computeIfAbsent(annotation.value(), ignored -> new java.util.ArrayList<>()).add(handler);
    }
    if (!owned.keySet().equals(channels.keySet())) {
      throw new IllegalStateException("Additional event owners must match the hosted subscribers");
    }
    var listeners = new java.util.ArrayList<EventListenerGroup>();
    var workers = new java.util.ArrayList<EventWorkers>();
    channels.forEach((owner, channel) -> {
      var subscriptions = owned.get(owner);
      var transport = new RabbitEventTransport(new RabbitTemplate(channel.connection()), codec, properties.getConfirmTimeout());
      workers.add(new EventWorkers(new EventDispatcher(channel.store(), transport, subscriptions), properties));
      listeners.add(EventInfrastructureConfiguration.listeners(
          channel.connection(), channel.store(), codec, subscriptions, owner, false));
    });
    return new SmartLifecycle() {
      private boolean running;

      @Override
      public void start() {
        try {
          listeners.forEach(EventListenerGroup::start);
          workers.forEach(EventWorkers::start);
          running = true;
        } catch (RuntimeException failure) {
          stop();
          throw failure;
        }
      }

      @Override
      public void stop() {
        listeners.forEach(EventListenerGroup::stop);
        workers.forEach(EventWorkers::stop);
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }

  private Channel require(String owner) {
    var channel = channels.get(owner);
    if (channel == null) throw new IllegalStateException("Additional event owner requires credentials: " + owner);
    return channel;
  }

  @Override
  public void destroy() {
    channels.values().forEach(channel -> channel.connection().destroy());
  }

  private record Channel(JdbcEventStore store, CachingConnectionFactory connection) {}
}
