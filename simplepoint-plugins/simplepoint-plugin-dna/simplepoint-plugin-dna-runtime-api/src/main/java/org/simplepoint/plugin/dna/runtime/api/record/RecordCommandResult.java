package org.simplepoint.plugin.dna.runtime.api.record;

import java.time.Instant;
import java.util.Objects;

/** Identity and server-managed values returned after one successful mutation. */
public record RecordCommandResult(
    RecordMutationType operation,
    String recordId,
    long version,
    int affectedRows,
    Instant updatedAt
) {

  /** Validates the mutation result contract. */
  public RecordCommandResult {
    Objects.requireNonNull(operation, "operation");
    recordId = RecordQueryLimits.recordId(recordId);
    if (version < 0 || affectedRows < 0) {
      throw new IllegalArgumentException("Record mutation result is invalid");
    }
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
