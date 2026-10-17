/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.EventDispatcher;
import org.simplepoint.events.jdbc.EventRetryPolicy;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/** Optional infrastructure adapter; business modules only import the event API. */
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EventInfrastructureProperties.class)
public class EventInfrastructureConfiguration {
  @Bean
  EventCodec platformEventCodec() {
    return new EventCodec();
  }

  @Bean
  JdbcEventStore platformEventStore(DataSource dataSource, PlatformTransactionManager manager,
                                   EventCodec codec, EventInfrastructureProperties properties) {
    if (properties.getBatchSize() < 1 || properties.getBatchSize() > 100
        || properties.getDispatchDelay().toMillis() < 100
        || properties.getConfirmTimeout().toMillis() < 100
        || properties.getConfirmTimeout().toSeconds() > 30
        || properties.getLease().compareTo(properties.getConfirmTimeout().multipliedBy(2)) < 0) {
      throw new IllegalArgumentException("Invalid event worker bounds");
    }
    JdbcEventStore store = new JdbcEventStore(dataSource, manager, codec, properties.getService(),
        Clock.systemUTC(), new EventRetryPolicy(properties.getMaxAttempts(), properties.getLease(),
            properties.getInitialRetryDelay(), properties.getMaxRetryDelay()));
    store.count("INBOX", "PENDING"); // Fail startup on missing migration, never lazy-create tables.
    return store;
  }

  @Bean
  RabbitEventTransport platformEventTransport(ConnectionFactory factory, EventCodec codec,
                                               EventInfrastructureProperties properties) {
    if (!(factory instanceof CachingConnectionFactory caching)
        || !caching.isPublisherConfirms() || !caching.isPublisherReturns()
        || !properties.getService().equals(factory.getUsername())) {
      throw new IllegalStateException("Events require correlated confirms, returns and service user");
    }
    return new RabbitEventTransport(new RabbitTemplate(factory), codec,
        properties.getConfirmTimeout());
  }

  @Bean
  EventDispatcher platformEventDispatcher(JdbcEventStore store, RabbitEventTransport transport,
                                          List<EventHandler> handlers) {
    return new EventDispatcher(store, transport, handlers);
  }

  @Bean
  EventWorkers platformEventWorkers(EventDispatcher dispatcher,
                                    EventInfrastructureProperties properties) {
    return new EventWorkers(dispatcher, properties);
  }

  @Bean
  Declarables platformEventTopology(List<EventHandler> handlers,
                                     EventInfrastructureProperties properties) {
    List<org.springframework.amqp.core.Declarable> declarations = new ArrayList<>();
    for (EventHandler handler : handlers) {
      var subscription = handler.subscription();
      DirectExchange exchange = new DirectExchange(
          RabbitEventTransport.exchange(subscription.producer()), true, false);
      Queue queue = QueueBuilder.durable(queueName(properties.getService(), subscription.id()))
          .quorum().withArgument("x-delivery-limit", -1).build();
      var binding = BindingBuilder.bind(queue).to(exchange).with(
          RabbitEventTransport.routing(subscription.eventType(), subscription.schemaVersion()));
      for (var declaration : List.of(exchange, queue, binding)) {
        declaration.setShouldDeclare(properties.isDeclareTopology());
        declarations.add(declaration);
      }
    }
    return new Declarables(declarations);
  }

  @Bean
  EventListenerGroup platformEventListeners(ConnectionFactory factory, JdbcEventStore store,
                                             EventCodec codec, List<EventHandler> handlers,
                                             EventInfrastructureProperties properties) {
    List<SimpleMessageListenerContainer> containers = new ArrayList<>();
    for (EventHandler handler : handlers) {
      SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(factory);
      container.setQueueNames(queueName(properties.getService(), handler.subscription().id()));
      container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
      container.setPrefetchCount(8);
      container.setConcurrentConsumers(1);
      container.setMaxConcurrentConsumers(2);
      container.setAutoDeclare(properties.isDeclareTopology());
      container.setMissingQueuesFatal(false);
      container.setDefaultRequeueRejected(true);
      container.setErrorHandler(error -> org.slf4j.LoggerFactory.getLogger(
          EventInfrastructureConfiguration.class).warn("Event receipt deferred ({})",
          error.getClass().getSimpleName()));
      RabbitEventReceiver receiver = new RabbitEventReceiver(handler.subscription(), store, codec);
      container.setMessageListener((org.springframework.amqp.rabbit.listener.api
          .ChannelAwareMessageListener) (message, channel) -> {
            try {
              receiver.onMessage(message, channel);
            } catch (RuntimeException ex) {
              // Bound redelivery load during a local database outage; no ACK has been issued.
              Thread.sleep(1000);
              throw new org.springframework.amqp.ImmediateRequeueAmqpException(
                  "Event reception unavailable", ex);
            }
          });
      containers.add(container);
    }
    return new EventListenerGroup(containers);
  }

  @Bean
  MeterBinder platformEventMetrics(JdbcEventStore store) {
    return registry -> {
      for (String direction : List.of("INBOX", "OUTBOX")) {
        for (String state : List.of("PENDING", "PROCESSING", "DEAD")) {
          Gauge.builder("simplepoint.events.jobs", store, value -> value.count(direction, state))
              .tag("direction", direction).tag("state", state).register(registry);
        }
      }
    };
  }

  /** Stable per-service subscriber queue shared by replicas. */
  public static String queueName(String owner, String subscription) {
    return "sp.events." + owner + "." + subscription;
  }
}
