/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

class RabbitEventReceiverTest {
  private final JdbcEventStore store = mock(JdbcEventStore.class);
  private final Channel channel = mock(Channel.class);
  private final EventCodec codec = new EventCodec();
  private final EventSubscription subscription = new EventSubscription("rbac.offline", "app",
      "resource.offline.requested", 1);
  private final RabbitEventReceiver receiver = new RabbitEventReceiver(subscription, store, codec);
  private final EventMessage event = new EventMessage(UUID.randomUUID(), subscription.eventType(),
      1, "app", "tenant", "resource", 1, "op", "trace", Instant.now(),
      JsonNodeFactory.instance.objectNode());

  @Test
  void acknowledgementFollowsDurableReceipt() throws Exception {
    receiver.onMessage(message(), channel);
    var ordered = inOrder(store, channel);
    ordered.verify(store).receive(subscription, event);
    ordered.verify(channel).basicAck(7, false);
  }

  @Test
  void databaseFailureNeverAcknowledges() throws Exception {
    doThrow(new DataAccessResourceFailureException("offline")).when(store).receive(any(), any());
    assertThrows(DataAccessResourceFailureException.class,
        () -> receiver.onMessage(message(), channel));
    verifyNoInteractions(channel);
  }

  @Test
  void forgedPublisherIsQuarantinedBeforeAck() throws Exception {
    Message message = message();
    message.getMessageProperties().setReceivedUserId("attacker");
    receiver.onMessage(message, channel);
    var ordered = inOrder(store, channel);
    ordered.verify(store).quarantine(subscription.id(), message.getBody());
    ordered.verify(channel).basicAck(7, false);
    verify(store, never()).receive(any(), any());
  }

  @Test
  void poisonStorageFailureNeverAcknowledges() {
    Message message = message();
    message.getMessageProperties().setReceivedUserId("attacker");
    doThrow(new DataAccessResourceFailureException("offline")).when(store).quarantine(any(), any());
    assertThrows(DataAccessResourceFailureException.class,
        () -> receiver.onMessage(message, channel));
    verifyNoInteractions(channel);
  }

  private Message message() {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(7);
    properties.setReceivedUserId("app");
    properties.setReceivedExchange(RabbitEventTransport.exchange("app"));
    properties.setReceivedRoutingKey(RabbitEventTransport.routing(subscription.eventType(), 1));
    properties.setMessageId(event.eventId().toString());
    return new Message(codec.encode(event).getBytes(StandardCharsets.UTF_8), properties);
  }
}
