package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.HashSet;
import java.util.List;

/** Bounded ordered mutation group committed atomically for one physical model. */
public record RecordTransactionCommand(
    String physicalModelId,
    List<RecordMutation> mutations,
    int timeoutMs
) {

  /** Validates transaction size, references, and timeout. */
  public RecordTransactionCommand {
    physicalModelId = RecordQueryLimits.modelId(physicalModelId);
    mutations = mutations == null ? List.of() : List.copyOf(mutations);
    if (mutations.isEmpty() || mutations.size() > 100) {
      throw new IllegalArgumentException("Record transaction requires 1 to 100 mutations");
    }
    if (new HashSet<>(mutations.stream().map(RecordMutation::reference).toList()).size()
        != mutations.size()) {
      throw new IllegalArgumentException("Mutation references must be unique");
    }
    if (timeoutMs < 1 || timeoutMs > 120_000) {
      throw new IllegalArgumentException("Record transaction timeout is invalid");
    }
  }
}
