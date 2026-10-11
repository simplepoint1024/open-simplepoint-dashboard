package org.simplepoint.plugin.dna.runtime.api.record;

/** Ordered optimistic soft delete inside a record transaction. */
public record RecordDeleteMutation(
    String reference,
    String recordId,
    long expectedVersion
) implements RecordMutation {

  /** Validates correlation identity, record identity, and version. */
  public RecordDeleteMutation {
    if (reference == null || reference.isBlank() || reference.length() > 64) {
      throw new IllegalArgumentException("Mutation reference is required");
    }
    reference = reference.trim();
    recordId = RecordQueryLimits.recordId(recordId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion cannot be negative");
    }
  }
}
