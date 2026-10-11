package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.List;

/** Immutable record page with a count produced from the identical predicate. */
public record RecordPageView(
    int page,
    int size,
    long total,
    List<RecordView> records
) {

  /** Defensively copies records and validates counters. */
  public RecordPageView {
    if (page < 0 || size < 1 || total < 0) {
      throw new IllegalArgumentException("Record page metadata is invalid");
    }
    records = records == null ? List.of() : List.copyOf(records);
  }
}
