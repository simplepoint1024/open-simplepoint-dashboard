package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Map;

/** Aggregate-only inspection; no record identities or business values leave DNA. */
public record RecordCompletenessView(long recordCount, Map<String, Long> emptyCounts) {

  /** Rejects malformed aggregates rather than interpreting an invalid result as safe. */
  public RecordCompletenessView {
    emptyCounts = Map.copyOf(emptyCounts);
    if (recordCount < 0 || emptyCounts.isEmpty() || emptyCounts.size() > 100
        || emptyCounts.values().stream().anyMatch(count -> count < 0 || count > recordCount)) {
      throw new IllegalArgumentException("Invalid record completeness aggregate");
    }
  }
}
