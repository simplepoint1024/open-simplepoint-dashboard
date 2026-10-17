/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.rabbit;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.jdbc.EventCodec;
import org.simplepoint.events.jdbc.JdbcEventStore;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/** Broker acknowledgement means durable receipt; business completion is a separate event. */
public final class RabbitEventReceiver implements ChannelAwareMessageListener {
  private final EventSubscription subscription;
  private final JdbcEventStore store;
  private final EventCodec codec;

  /** Binds durable receipt to one explicitly trusted subscription contract. */
  public RabbitEventReceiver(EventSubscription subscription, JdbcEventStore store, EventCodec codec) {
    this.subscription = subscription;
    this.store = store;
    this.codec = codec;
  }

  @Override
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      var properties = message.getMessageProperties();
      if (!subscription.producer().equals(properties.getReceivedUserId())
          || !RabbitEventTransport.exchange(subscription.producer())
              .equals(properties.getReceivedExchange())
          || !RabbitEventTransport.routing(subscription.eventType(), subscription.schemaVersion())
              .equals(properties.getReceivedRoutingKey())) {
        throw new IllegalArgumentException("Untrusted event origin or route");
      }
      EventMessage event = codec.decode(message.getBody());
      if (!event.eventId().toString().equals(properties.getMessageId())) {
        throw new IllegalArgumentException("Event identity header mismatch");
      }
      store.receive(subscription, event);
    } catch (IllegalArgumentException invalid) {
      // Poison messages are stored locally before ACK, avoiding unreliable reject-to-DLX transfer.
      store.quarantine(subscription.id(), message.getBody());
    }
    // Database outages propagate without ACK. Container requeues and applies recovery backoff.
    channel.basicAck(tag, false);
  }
}
