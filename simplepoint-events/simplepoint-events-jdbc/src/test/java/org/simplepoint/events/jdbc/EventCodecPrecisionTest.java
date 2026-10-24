package org.simplepoint.events.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;

class EventCodecPrecisionTest {
  @Test
  void repeatedTransportRetainsDecimalPayloadsAndNanosecondEnvelope() {
    var timestamp = Instant.parse("2026-10-22T02:09:07.395230750Z");
    var amount = new BigDecimal("98765432109876543210.123456789");
    var seconds = new BigDecimal("1792634947.395230750");
    var payload = JsonNodeFactory.instance.objectNode();
    payload.put("amount", amount);
    payload.put("loginAt", seconds);
    var message = new EventMessage(UUID.randomUUID(), "precision.test", 1, "test",
        "tenant", "resource", 0, "operation", "correlation", timestamp, payload);
    var codec = new EventCodec();
    for (int i = 0; i < 3; i++) {
      message = codec.decode(codec.encode(message).getBytes(StandardCharsets.UTF_8));
      assertThat(message.occurredAt()).isEqualTo(timestamp);
      assertThat(message.payload().get("amount").decimalValue()).isEqualByComparingTo(amount);
      assertThat(message.payload().get("loginAt").decimalValue()).isEqualByComparingTo(seconds);
    }
  }
}
