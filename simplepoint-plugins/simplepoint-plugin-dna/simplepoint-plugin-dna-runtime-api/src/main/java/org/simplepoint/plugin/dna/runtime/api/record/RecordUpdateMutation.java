package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;

/** Ordered optimistic update inside a record transaction. */
public record RecordUpdateMutation(
    String reference,
    String recordId,
    long expectedVersion,
    Map<String, Object> values
) implements RecordMutation {

  /** Validates correlation identity, record identity, version, and values. */
  public RecordUpdateMutation {
    reference = requireReference(reference);
    recordId = RecordQueryLimits.recordId(recordId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion cannot be negative");
    }
    values = RecordValues.copy(values);
    if (values.isEmpty()) {
      throw new IllegalArgumentException("Record update values are required");
    }
  }

  private static String requireReference(final String value) {
    if (value == null || value.isBlank() || value.length() > 64) {
      throw new IllegalArgumentException("Mutation reference is required");
    }
    return value.trim();
  }
}
