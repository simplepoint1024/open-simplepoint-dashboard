package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;

/** Optimistically updates one active record. */
public record RecordUpdateCommand(
    String physicalModelId,
    String recordId,
    long expectedVersion,
    Map<String, Object> values
) {

  /** Validates identity, expected version, and field keys. */
  public RecordUpdateCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    recordId = RecordQueryLimits.recordId(recordId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion cannot be negative");
    }
    values = RecordValues.copy(values);
    if (values.isEmpty()) {
      throw new IllegalArgumentException("Record update values are required");
    }
  }
}
