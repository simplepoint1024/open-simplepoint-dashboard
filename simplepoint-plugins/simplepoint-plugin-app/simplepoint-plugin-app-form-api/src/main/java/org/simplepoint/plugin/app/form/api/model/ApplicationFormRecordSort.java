package org.simplepoint.plugin.app.form.api.model;

import java.util.Objects;

/** One field-identity based runtime record ordering. */
public record ApplicationFormRecordSort(
    String fieldId,
    Direction direction
) {

  /** Requires an explicit field and direction. */
  public ApplicationFormRecordSort {
    if (fieldId == null || fieldId.isBlank()) {
      throw new IllegalArgumentException("fieldId is required");
    }
    Objects.requireNonNull(direction, "direction");
  }

  /** Portable sort direction. */
  public enum Direction {
    ASC,
    DESC
  }
}
