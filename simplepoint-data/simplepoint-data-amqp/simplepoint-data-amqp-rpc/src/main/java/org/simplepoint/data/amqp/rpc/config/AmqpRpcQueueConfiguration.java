/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc.config;

import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A configuration class for setting up AMQP (Advanced Message Queuing Protocol)
 * RPC queues and bindings.
 * Defines beans for exchanges, queues, and their bindings using application-specific properties.
 */
@Configuration(proxyBeanMethods = false)
public class AmqpRpcQueueConfiguration {

  private final ArpcProperties properties;

  /**
   * Constructs a new instance of {@link AmqpRpcQueueConfiguration} with the provided properties.
   *
   * @param properties the AMQP RPC properties used for configuration
   */
  public AmqpRpcQueueConfiguration(
      final ArpcProperties properties
  ) {
    this.properties = properties;
  }

  /**
   * Defines the direct exchange used for routing messages based on header conditions.
   *
   * @return a {@link DirectExchange} instance configured with the exchange name
  */
  @Bean("simplepoint.queue.amqprpc.direct.exchange")
  public DirectExchange directExchange() {
    return new DirectExchange(properties.exchange(), true, false);
  }

  /**
   * Defines the dead-letter exchange used for rejected RPC requests.
   *
   * @return the dead-letter exchange
   */
  @Bean("simplepoint.queue.amqprpc.dead.letter.exchange")
  public DirectExchange deadLetterExchange() {
    return new DirectExchange(properties.deadLetterExchange(), true, false);
  }

  /**
   * Defines the request queue used for sending RPC requests.
   *
   * @return a {@link Queue} instance representing the request queue
  */
  @Bean("simplepoint.queue.amqprpc.request")
  public Queue requestQueue() {
    return QueueBuilder.durable(properties.requestQueue())
        .deadLetterExchange(properties.deadLetterExchange())
        .deadLetterRoutingKey(properties.deadLetterRoutingKey())
        .build();
  }

  /**
   * Defines the dead-letter queue used for rejected RPC requests.
   *
   * @return the dead-letter queue
   */
  @Bean("simplepoint.queue.amqprpc.dead.letter")
  public Queue deadLetterQueue() {
    return QueueBuilder.durable(properties.deadLetterQueue()).build();
  }

  /**
   * Creates a binding between the request queue and the direct exchange.
   * Specifies routing conditions based on the "to" header.
   *
   * @param requestQueue   the request queue bean
   * @param directExchange the direct exchange bean
   * @return a {@link Binding} instance representing the queue-to-exchange binding
   */
  @Bean
  public Binding requestBinding(
      @Qualifier("simplepoint.queue.amqprpc.request") Queue requestQueue,
      @Qualifier("simplepoint.queue.amqprpc.direct.exchange") DirectExchange directExchange) {
    return BindingBuilder.bind(requestQueue)
        .to(directExchange)
        .with(properties.requestRoutingKey());
  }

  /**
   * Creates a binding between the dead-letter queue and dead-letter exchange.
   *
   * @param deadLetterQueue the dead-letter queue bean
   * @param deadLetterExchange the dead-letter exchange bean
   * @return the dead-letter binding
   */
  @Bean
  public Binding deadLetterBinding(
      @Qualifier("simplepoint.queue.amqprpc.dead.letter") final Queue deadLetterQueue,
      @Qualifier("simplepoint.queue.amqprpc.dead.letter.exchange") final DirectExchange deadLetterExchange
  ) {
    return BindingBuilder.bind(deadLetterQueue)
        .to(deadLetterExchange)
        .with(properties.deadLetterRoutingKey());
  }

  /**
   * Builds a dedicated listener container factory for RPC request consumers.
   *
   * @param connectionFactory the RabbitMQ connection factory
   * @return the listener container factory
   */
  @Bean
  public SimpleRabbitListenerContainerFactory arpcListenerContainerFactory(
      final ConnectionFactory connectionFactory
  ) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setConcurrentConsumers(properties.getListener().getConcurrency());
    factory.setMaxConcurrentConsumers(properties.getListener().getMaxConcurrency());
    factory.setPrefetchCount(properties.getListener().getPrefetch());
    factory.setDefaultRequeueRejected(false);
    return factory;
  }
}
