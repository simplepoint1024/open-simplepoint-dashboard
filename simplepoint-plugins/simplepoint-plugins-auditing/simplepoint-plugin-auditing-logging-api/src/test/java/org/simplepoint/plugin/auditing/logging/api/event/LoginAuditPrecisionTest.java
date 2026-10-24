package org.simplepoint.plugin.auditing.logging.api.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;

class LoginAuditPrecisionTest {
  private static final Instant TIME = Instant.parse("2026-10-22T02:09:07.395230750Z");

  private ObjectNode payload() {
    var command = new LoginLogRecordCommand();
    command.setSourceService("authorization");
    command.setStatus("SUCCESS");
    command.setLoginAt(TIME);
    return (ObjectNode) LoginAuditContract.encode(command);
  }

  @Test
  void newPayloadRetainsNanosecondsAcrossAnOrdinaryJsonTransport() throws Exception {
    var json = new ObjectMapper();
    var transported = json.readTree(json.writeValueAsBytes(payload()));
    assertThat(transported.get("loginAt").isTextual()).isTrue();
    assertThat(LoginAuditContract.decode(transported, TIME).getLoginAt()).isEqualTo(TIME);
  }

  @Test
  void legacyDoubleRoundingRestoresOnlyTheExactEnvelopeTimestamp() throws Exception {
    var old = payload();
    old.put("loginAt", new BigDecimal("1792634947.395230750").doubleValue());
    var transported = new ObjectMapper().readTree(old.toString());
    assertThat(LoginAuditContract.decode(transported, TIME).getLoginAt()).isEqualTo(TIME);
    assertThatThrownBy(() -> LoginAuditContract.decode(transported, TIME.plusMillis(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactLegacyDecimalRemainsSupported() {
    var old = payload();
    old.put("loginAt", new BigDecimal("1792634947.395230750"));
    assertThat(LoginAuditContract.decode(old, TIME).getLoginAt()).isEqualTo(TIME);
  }

  @Test
  void newIsoPayloadCannotUseTheLegacyRoundingException() {
    var value = payload();
    value.put("loginAt", TIME.plusNanos(1).toString());
    assertThatThrownBy(() -> LoginAuditContract.decode(value, TIME))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void arbitraryNumericTimestampCannotUseTheLegacyRoundingException() {
    var value = payload();
    value.put("loginAt", new BigDecimal("1792634947.3952309"));
    assertThatThrownBy(() -> LoginAuditContract.decode(value, TIME))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
