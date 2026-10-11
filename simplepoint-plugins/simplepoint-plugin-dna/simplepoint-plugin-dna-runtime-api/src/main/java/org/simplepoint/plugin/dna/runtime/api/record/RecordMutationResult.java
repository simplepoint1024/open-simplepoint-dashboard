package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** Correlated result for one operation in an atomic command group. */
public record RecordMutationResult(String reference, RecordCommandResult result) {

  /** Validates the correlation result. */
  public RecordMutationResult {
    if (reference == null || reference.isBlank()) {
      throw new IllegalArgumentException("Mutation reference is required");
    }
    Objects.requireNonNull(result, "result");
  }
}
