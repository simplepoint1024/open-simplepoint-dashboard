package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable target-field dependency; labels and physical column names are deliberately excluded. */
public record LogicalReferenceField(String fieldId, LogicalFieldType type) {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

  public LogicalReferenceField {
    if (fieldId == null || !ID.matcher(fieldId).matches()) {
      throw new IllegalArgumentException("A reference dependency requires a stable field identity");
    }
    Objects.requireNonNull(type, "type");
  }
}
