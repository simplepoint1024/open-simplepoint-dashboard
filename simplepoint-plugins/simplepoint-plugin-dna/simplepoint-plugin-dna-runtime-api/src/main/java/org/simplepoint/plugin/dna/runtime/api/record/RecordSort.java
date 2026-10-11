package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** One validated field order requested by the form runtime. */
public record RecordSort(String field, RecordSortDirection direction) {

  /** Validates portable field identity and direction. */
  public RecordSort {
    if (field == null || !field.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("Record sort field has an invalid format");
    }
    Objects.requireNonNull(direction, "direction");
  }
}
