/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventTransport;
import org.simplepoint.events.jdbc.EventCodec;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Persistent, mandatory publications with correlated confirms and broker-validated user identity. */
public final class RabbitEventTransport implements EventTransport {
  private final RabbitTemplate template;
  private final EventCodec codec;
  private final Duration timeout;

  /** Creates a dedicated publisher that does not alter application RabbitTemplate beans. */
  public RabbitEventTransport(RabbitTemplate template, EventCodec codec, Duration timeout) {
    this.template = template;
    this.codec = codec;
    this.timeout = timeout;
    template.setMandatory(true);
  }

  @Override
  public void send(EventMessage event) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setContentEncoding("UTF-8");
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(event.eventId().toString());
    properties.setCorrelationId(event.correlationId());
    // RabbitMQ rejects user_id that differs from the authenticated connection username.
    properties.setUserId(event.producer());
    CorrelationData correlation = new CorrelationData();
    template.send(exchange(event.producer()), routing(event.eventType(), event.schemaVersion()),
        new Message(codec.encode(event).getBytes(StandardCharsets.UTF_8), properties), correlation);
    try {
      var confirmation = correlation.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!confirmation.ack() || correlation.getReturned() != null) {
        throw new IllegalStateException("Event publication rejected or unroutable");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Event confirmation interrupted", ex);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
      throw new IllegalStateException("Event confirmation uncertain", ex);
    }
  }

  /** Producer-owned exchange; broker ACLs restrict its writers. */
  public static String exchange(String producer) {
    return "sp.events." + EventMessage.token(producer, "producer");
  }

  /** Versioned routing key without transport-specific Java type names. */
  public static String routing(String type, int version) {
    return EventMessage.token(type, "event type") + ".v" + version;
  }
}
