/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.core;

import java.util.Collections;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

/**
 * A client class for sending and receiving RabbitMQ messages using {@link RabbitTemplate}.
 * Provides utility methods for publishing messages with customized properties and headers.
 *
 * @param rabbitTemplate The RabbitTemplate instance used for sending and receiving messages.
 */
public record MessageClient(RabbitTemplate rabbitTemplate) {

  /**
   * Constructs a {@link MessageClient} with the given {@link RabbitTemplate}.
   *
   * @param rabbitTemplate the RabbitTemplate instance to be used
   */
  public MessageClient {
  }

  /**
   * Sends a message to the specified exchange and routing key with custom properties and headers.
   *
   * @param exchange   the name of the exchange to send the message to
   * @param routingKey the routing key to be used
   * @param type       the type of the message
   * @param data       the payload of the message
   * @param headers    a map of custom headers to attach to the message
   */
  public void send(String exchange, String routingKey, String type, Object data,
                   Map<String, Object> headers) {
    MessageProperties messageProperties = createMessageProperties(null, type, headers);
    Message message = this.rabbitTemplate.getMessageConverter().toMessage(data, messageProperties);
    this.rabbitTemplate.send(exchange, routingKey, message);
  }

  /**
   * Sends a message to the specified exchange and routing key with a message type and payload.
   *
   * @param exchange   the name of the exchange to send the message to
   * @param routingKey the routing key to be used
   * @param type       the type of the message
   * @param data       the payload of the message
   */
  public void send(String exchange, String routingKey, String type, Object data) {
    send(exchange, routingKey, type, data, Collections.emptyMap());
  }

  /**
   * Sends a message to the specified exchange and routing key with just a payload.
   *
   * @param exchange   the name of the exchange to send the message to
   * @param routingKey the routing key to be used
   * @param data       the payload of the message
   */
  public void send(String exchange, String routingKey, Object data) {
    send(exchange, routingKey, null, data);
  }

  /**
   * Sends a message and waits for a reply from the recipient.
   *
   * @param <T>        the type of the expected reply
   * @param exchange   the name of the exchange to send the message to
   * @param routingKey the routing key to be used
   * @param replyTo    the reply-to address for the message
   * @param type       the type of the message
   * @param data       the payload of the message
   * @param headers    a map of custom headers to attach to the message
   * @return the response message converted to the specified type, or null if no response is received
   */
  public <T> T sendAndReceive(String exchange, String routingKey, String replyTo, String type,
                              Object data, Map<String, Object> headers, Class<T> returnType) {
    MessageProperties messageProperties = createMessageProperties(replyTo, type, headers);
    Message requestMessage =
        this.rabbitTemplate.getMessageConverter().toMessage(data, messageProperties);

    Message message = this.rabbitTemplate.sendAndReceive(exchange, routingKey, requestMessage);
    if (message == null) {
      return null;
    }
    return returnType.cast(this.rabbitTemplate.getMessageConverter().fromMessage(message));
  }

  /**
   * Sends a message and waits for a reply, specifying the reply-to address and type.
   *
   * @param <T>        the type of the expected reply
   * @param exchange   the name of the exchange to send the message to
   * @param routingKey the routing key to be used
   * @param replyTo    the reply-to address for the message
   * @param type       the type of the message
   * @param data       the payload of the message
   * @return the response message converted to the specified type
   */
  public <T> T sendAndReceive(String exchange, String routingKey, String replyTo, String type,
                              Object data, Class<T> returnType) {
    return sendAndReceive(exchange, routingKey, type, replyTo, data, Collections.emptyMap(),
        returnType);
  }

  /**
   * Sends a message and waits for a reply, specifying the reply-to address without a type.
   *
   * @param <T>        the type of the expected reply
   * @param exchange   the name of the exchange to send the message to
   * @param routingKey the routing key to be used
   * @param replyTo    the reply-to address for the message
   * @param data       the payload of the message
   * @return the response message converted to the specified type
   */
  public <T> T sendAndReceive(String exchange, String routingKey, String replyTo, Object data, Class<T> returnType) {
    return sendAndReceive(exchange, routingKey, replyTo, null, data, returnType);
  }

  /**
   * Creates a {@link MessageProperties} object with the specified reply-to address, type, and headers.
   *
   * @param replyTo the reply-to address for the message
   * @param type    the type of the message
   * @param headers a map of custom headers to attach to the message
   * @return a new {@link MessageProperties} object with the specified configurations
   */
  private MessageProperties createMessageProperties(String replyTo, String type,
                                                    Map<String, Object> headers) {
    MessageProperties messageProperties = new MessageProperties();
    if (replyTo != null) {
      messageProperties.setReplyTo(replyTo);
    }
    messageProperties.setContentEncoding("UTF-8");
    messageProperties.setCorrelationId(rabbitTemplate.getUUID());
    messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    messageProperties.setType(type);
    messageProperties.setHeaders(headers);
    return messageProperties;
  }

  /**
   * Retrieves the {@link RabbitTemplate} instance used by this client.
   *
   * @return the {@link RabbitTemplate} instance
   */
  @Override
  public RabbitTemplate rabbitTemplate() {
    return this.rabbitTemplate;
  }

  /**
   * Retrieves the {@link MessageConverter} instance used by this client.
   *
   * @return the {@link MessageConverter} instance
   */
  public MessageConverter getMessageConverter() {
    return this.rabbitTemplate.getMessageConverter();
  }
}
