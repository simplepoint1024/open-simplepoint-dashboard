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
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.Queue;
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
   * @return a {@link HeadersExchange} instance configured with the exchange name
   */
  @Bean("rpcDirectExchange")
  public HeadersExchange directExchange() {
    return new HeadersExchange(properties.prefix(properties.getExchangeName()), true, false);
  }

  /**
   * Defines the request queue used for sending RPC requests.
   *
   * @return a {@link Queue} instance representing the request queue
   */
  @Bean("rpcRequestQueue")
  public Queue requestQueue() {
    return new Queue(properties.prefix(properties.getAppId()), true, false, false);
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
      @Qualifier("rpcRequestQueue") Queue requestQueue,
      @Qualifier("rpcDirectExchange") HeadersExchange directExchange) {
    return BindingBuilder.bind(requestQueue)
        .to(directExchange)
        .where("to")
        .matches(properties.prefix(properties.getAppId()));
  }

  /**
   * Defines the reply-to queue used for receiving RPC responses.
   *
   * @return a {@link Queue} instance representing the reply-to queue
   */
  @Bean("rpcReplyToQueue")
  public Queue replyToQueue() {
    return new Queue(properties.prefix(properties.getResponseQueueName()), true, false, false);
  }

  /**
   * Creates a binding between the reply-to queue and the direct exchange.
   * Specifies routing conditions based on the "to" header.
   *
   * @param replyToQueue   the reply-to queue bean
   * @param directExchange the direct exchange bean
   * @return a {@link Binding} instance representing the queue-to-exchange binding for replies
   */
  @Bean
  public Binding replyToBinding(
      @Qualifier("rpcReplyToQueue") Queue replyToQueue,
      @Qualifier("rpcDirectExchange") HeadersExchange directExchange) {
    return BindingBuilder.bind(replyToQueue)
        .to(directExchange)
        .where("to")
        .matches(properties.prefix(properties.getAppId()));
  }
}
