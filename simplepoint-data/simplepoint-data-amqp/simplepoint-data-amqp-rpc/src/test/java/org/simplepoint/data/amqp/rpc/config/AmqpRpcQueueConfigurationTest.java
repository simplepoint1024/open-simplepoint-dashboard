package org.simplepoint.data.amqp.rpc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.springframework.amqp.core.Queue;

class AmqpRpcQueueConfigurationTest {

  @Test
  void requestQueueShouldDeclareDeadLetterRouting() {
    ArpcProperties properties = new ArpcProperties();
    properties.setPrefix("simplepoint.arpc.");
    properties.setExchangeName("exchange.direct");
    properties.setRequestQueueName("common");
    AmqpRpcQueueConfiguration configuration = new AmqpRpcQueueConfiguration(properties);

    Queue requestQueue = configuration.requestQueue();

    assertEquals("simplepoint.arpc.common", requestQueue.getName());
    assertEquals("simplepoint.arpc.exchange.dlx", requestQueue.getArguments().get("x-dead-letter-exchange"));
    assertEquals("simplepoint.arpc.common.dlq", requestQueue.getArguments().get("x-dead-letter-routing-key"));
    assertEquals("simplepoint.arpc.common.dlq", configuration.deadLetterQueue().getName());
  }
}
