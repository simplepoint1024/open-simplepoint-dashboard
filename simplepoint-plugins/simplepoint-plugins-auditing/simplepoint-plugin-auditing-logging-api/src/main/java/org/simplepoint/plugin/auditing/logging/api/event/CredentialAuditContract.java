package org.simplepoint.plugin.auditing.logging.api.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;

/**
 * Versioned credential outcome; no submitted username, password, OTP or session handle is allowed.
 */
public final class CredentialAuditContract {
  public static final String PRODUCER = "common";
  public static final EventPublication PUBLICATION =
      new EventPublication("security.credential-audit", 1);
  public static final EventSubscription SUBSCRIPTION =
      new EventSubscription(
          "credential-audit", PRODUCER, PUBLICATION.eventType(), PUBLICATION.schemaVersion());
  private static final ObjectMapper JSON = new ObjectMapper();

  private CredentialAuditContract() {}

  /** Encodes only the bounded explicit schema. */
  public static JsonNode encode(Outcome outcome) {
    validate(outcome);
    return JSON.valueToTree(outcome);
  }

  /** Strictly decodes the schema; diagnostics never echo invalid content. */
  public static Outcome decode(JsonNode payload) {
    try {
      Outcome value = JSON.treeToValue(payload, Outcome.class);
      validate(value);
      return value;
    } catch (Exception invalid) {
      throw new IllegalArgumentException("Invalid credential audit payload");
    }
  }

  private static void validate(Outcome value) {
    if (value == null
        || value.caller() == null
        || value.caller().isBlank()
        || value.caller().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 160
        || value.subjectFingerprint() == null
        || !value.subjectFingerprint().matches("[a-f0-9]{64}")
        || value.status() == null
        || !java.util.Set.of("SUCCESS", "FAILURE").contains(value.status())
        || value.reason() == null
        || !java.util.Set.of("VERIFIED", "INVALID", "UNAVAILABLE").contains(value.reason())
        || ("SUCCESS".equals(value.status()) != "VERIFIED".equals(value.reason()))
        || ("SUCCESS".equals(value.status())
            && (value.userId() == null || value.userId().isBlank()))
        || (value.userId() != null && value.userId().length() > 64)
        || value.occurredAt() <= 0) {
      throw new IllegalArgumentException("Invalid credential audit scope or outcome");
    }
    Instant.ofEpochMilli(value.occurredAt());
  }

  /** Safe identity/outcome fields only; timestamp uses an explicit epoch millisecond contract. */
  public record Outcome(
      String caller,
      String userId,
      String subjectFingerprint,
      String status,
      String reason,
      long occurredAt) {}
}
