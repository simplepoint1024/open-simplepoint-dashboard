package org.simplepoint.plugin.dna.runtime.api.audit;

import java.time.Instant;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;

/** Sanitized control-plane event. Business values and credentials are intentionally absent. */
public record DnaRuntimeAuditEvent(
    String eventId,
    DnaRuntimeAuditEventType eventType,
    Instant occurredAt,
    String tenantId,
    String actorId,
    String physicalModelId,
    String appId,
    String formId,
    String dataSourceId,
    Integer modelVersion,
    Long affectedRows,
    long durationMillis,
    DnaRuntimeAuditOutcome outcome,
    DnaRuntimeErrorCode errorCode
) {

  /** Validates identity and outcome consistency without accepting business values. */
  public DnaRuntimeAuditEvent {
    requireText(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    requireText(tenantId, "tenantId");
    requireText(actorId, "actorId");
    requireText(physicalModelId, "physicalModelId");
    requireText(dataSourceId, "dataSourceId");
    Objects.requireNonNull(outcome, "outcome");
    if (affectedRows != null && affectedRows < 0) {
      throw new IllegalArgumentException("affectedRows cannot be negative");
    }
    if (durationMillis < 0) {
      throw new IllegalArgumentException("durationMillis cannot be negative");
    }
    if (outcome == DnaRuntimeAuditOutcome.SUCCEEDED && errorCode != null) {
      throw new IllegalArgumentException("Successful audit events cannot carry an error code");
    }
    if (outcome != DnaRuntimeAuditOutcome.SUCCEEDED && errorCode == null) {
      throw new IllegalArgumentException("Rejected or failed events require an error code");
    }
  }

  private static void requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
