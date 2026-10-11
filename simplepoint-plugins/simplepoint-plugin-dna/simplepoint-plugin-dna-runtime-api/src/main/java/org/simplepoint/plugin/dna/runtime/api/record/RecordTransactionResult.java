package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;

/** Results committed by one physical transaction. */
public record RecordTransactionResult(List<RecordMutationResult> results, long executionTimeMs) {

  /** Defensively copies results and validates duration. */
  public RecordTransactionResult {
    results = results == null ? List.of() : List.copyOf(results);
    if (executionTimeMs < 0) {
      throw new IllegalArgumentException("Transaction duration cannot be negative");
    }
  }
}
