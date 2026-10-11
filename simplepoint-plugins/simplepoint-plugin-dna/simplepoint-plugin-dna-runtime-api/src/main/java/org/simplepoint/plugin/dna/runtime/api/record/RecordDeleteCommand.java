package org.simplepoint.plugin.dna.runtime.api.record;

/** Optimistically soft-deletes one active record. */
public record RecordDeleteCommand(
    String physicalModelId,
    String recordId,
    long expectedVersion
) {

  /** Validates identity and expected version. */
  public RecordDeleteCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    recordId = RecordQueryLimits.recordId(recordId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion cannot be negative");
    }
  }
}
