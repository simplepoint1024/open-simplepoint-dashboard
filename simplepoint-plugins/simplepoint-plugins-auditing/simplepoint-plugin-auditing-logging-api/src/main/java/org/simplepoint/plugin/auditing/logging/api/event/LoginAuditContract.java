package org.simplepoint.plugin.auditing.logging.api.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;

/**
 * Explicit v1 contract: no credentials, session cookies, Java type metadata or authority snapshots.
 */
public final class LoginAuditContract {
  public static final String EVENT_TYPE = "security.login-audit";
  public static final EventPublication PUBLICATION = new EventPublication(EVENT_TYPE, 1);
  public static final EventSubscription SUBSCRIPTION =
      new EventSubscription("login-audit", "authorization", EVENT_TYPE, 1);
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private LoginAuditContract() {}

  /** Validates and encodes the bounded login audit payload. */
  public static JsonNode encode(LoginLogRecordCommand command) {
    validate(command);
    return JSON.valueToTree(command);
  }

  /** Rejects malformed, unsupported or unbounded event payloads. */
  public static LoginLogRecordCommand decode(JsonNode payload) {
    try {
      LoginLogRecordCommand command = JSON.treeToValue(payload, LoginLogRecordCommand.class);
      validate(command);
      return command;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid login audit v1 payload");
    }
  }

  /**
   * Checks the timestamp against the authenticated envelope. Old v1 numeric timestamps passed
   * through a double-valued JSON tree; accept only that exact rounding of the envelope timestamp.
   * New ISO timestamps must match exactly. The immutable envelope retains the original precision.
   */
  public static LoginLogRecordCommand decode(JsonNode payload, Instant occurredAt) {
    var command = decode(payload);
    if (!occurredAt.equals(command.getLoginAt())) {
      JsonNode timestamp = payload.get("loginAt");
      BigDecimal seconds = BigDecimal.valueOf(occurredAt.getEpochSecond())
          .add(BigDecimal.valueOf(occurredAt.getNano(), 9));
      BigDecimal legacy = BigDecimal.valueOf(seconds.doubleValue());
      if (!timestamp.isFloatingPointNumber()
          || timestamp.decimalValue().compareTo(legacy) != 0) {
        throw new IllegalArgumentException("Inconsistent login audit timestamp");
      }
      command.setLoginAt(occurredAt);
    }
    return command;
  }

  private static void validate(LoginLogRecordCommand command) {
    if (!java.util.Set.of("SUCCESS", "FAILURE").contains(command.getStatus())
        || !"authorization".equals(command.getSourceService())
        || command.getLoginAt() == null
        || command.getTenantId() != null
        || command.getContextId() != null) {
      throw new IllegalArgumentException("Invalid login audit scope or outcome");
    }
    bounded(command.getUserId(), 64);
    bounded(command.getUsername(), 128);
    bounded(command.getDisplayName(), 128);
    bounded(command.getClientIp(), 64);
    bounded(command.getRemoteAddress(), 64);
    bounded(command.getForwardedFor(), 512);
    bounded(command.getUserAgent(), 2048);
    bounded(command.getRequestUri(), 512);
    bounded(command.getFailureReason(), 512);
    bounded(command.getLoginType(), 32);
    bounded(command.getAuthenticationType(), 128);
    if (command.getSessionId() != null && !command.getSessionId().matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Only a session fingerprint may be audited");
    }
  }

  private static void bounded(String value, int maximum) {
    if (value != null && value.length() > maximum) {
      throw new IllegalArgumentException("Login audit field exceeds maximum length");
    }
  }
}
