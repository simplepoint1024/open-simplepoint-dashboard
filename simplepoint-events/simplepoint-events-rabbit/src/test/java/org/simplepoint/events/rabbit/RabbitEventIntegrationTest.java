/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.jdbc.EventCodec;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Real broker tests use a disposable vhost/server; credentials are provided only by environment. */
@EnabledIfEnvironmentVariable(named = "SP_EVENT_TEST_RABBIT_PASSWORD", matches = ".+")
class RabbitEventIntegrationTest {
  private CachingConnectionFactory connection;
  private RabbitAdmin admin;
  private RabbitTemplate template;
  private RabbitEventTransport transport;
  private String queue;
  private String eventType;

  @BeforeEach
  void setUp() {
    connection = new CachingConnectionFactory("127.0.0.1", 35672);
    connection.setUsername("app");
    connection.setPassword(System.getenv("SP_EVENT_TEST_RABBIT_PASSWORD"));
    connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    connection.setPublisherReturns(true);
    template = new RabbitTemplate(connection);
    admin = new RabbitAdmin(connection);
    transport = new RabbitEventTransport(template, new EventCodec(), Duration.ofSeconds(5));
    eventType = "test." + UUID.randomUUID();
    queue = "sp.test." + UUID.randomUUID();
    var exchange = new DirectExchange(RabbitEventTransport.exchange("app"), true, false);
    Queue target = QueueBuilder.durable(queue).quorum().build();
    admin.declareExchange(exchange);
    admin.declareQueue(target);
    admin.declareBinding(BindingBuilder.bind(target).to(exchange)
        .with(RabbitEventTransport.routing(eventType, 1)));
  }

  @AfterEach
  void tearDown() {
    admin.deleteQueue(queue);
    connection.destroy();
  }

  @Test
  void confirmedPersistentDeliverySurvivesAbsentConsumerAndRedelivery() throws Exception {
    EventMessage event = event(eventType);
    transport.send(event); // There is deliberately no running consumer.
    connection.resetConnection();
    var message = template.receive(queue, 5000);
    assertNotNull(message);
    assertEquals(event, new EventCodec().decode(message.getBody()));
    assertEquals("app", message.getMessageProperties().getReceivedUserId());
    assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,
        message.getMessageProperties().getReceivedDeliveryMode());
    // Simulate an uncertain confirm retry using exactly the original identity and payload.
    transport.send(event);
    var repeated = template.receive(queue, 5000);
    assertEquals(event, new EventCodec().decode(repeated.getBody()));
    assertNull(template.receive(queue, 100));
  }

  @Test
  void unroutableMessageIsNotReportedAsDelivered() {
    assertThrows(IllegalStateException.class, () -> transport.send(event("unbound." + UUID.randomUUID())));
  }

  @Test
  void unacknowledgedDeliveryReturnsAfterChannelLoss() throws Exception {
    EventMessage event = event(eventType);
    transport.send(event);
    var rabbitConnection = connection.createConnection();
    var channel = rabbitConnection.createChannel(false);
    var delivery = channel.basicGet(queue, false);
    assertNotNull(delivery);
    channel.abort();
    var redelivered = template.receive(queue, 5000);
    assertNotNull(redelivered);
    assertTrue(redelivered.getMessageProperties().isRedelivered());
    assertEquals(event, new EventCodec().decode(redelivered.getBody()));
  }

  @Test
  void brokerRejectsSpoofedUserIdentity() {
    EventMessage legitimate = event(eventType);
    var forged = new EventMessage(legitimate.eventId(), eventType, 1, "other", "tenant", "resource",
        1, "op", "trace", legitimate.occurredAt(), legitimate.payload());
    admin.declareExchange(new DirectExchange(RabbitEventTransport.exchange("other"), true, false));
    try {
      assertThrows(RuntimeException.class, () -> transport.send(forged));
    } finally {
      admin.deleteExchange(RabbitEventTransport.exchange("other"));
    }
  }

  private EventMessage event(String type) {
    return new EventMessage(UUID.randomUUID(), type, 1, "app", "tenant", "resource", 1,
        "op", "trace", Instant.now(), JsonNodeFactory.instance.objectNode().put("name", "中文日本語"));
  }
}
