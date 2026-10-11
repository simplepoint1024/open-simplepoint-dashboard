package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.Objects;

/** Normalized physical column used for drift detection across JDBC drivers. */
public record PhysicalColumnSnapshot(
    String name,
    PhysicalStorageType storageType,
    Integer length,
    Integer precision,
    Integer scale,
    boolean nullable,
    String defaultValue,
    String comment
) {

  /** Validates normalized column identity and type. */
  public PhysicalColumnSnapshot {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Column name is required");
    }
    name = name.trim().toLowerCase(java.util.Locale.ROOT);
    Objects.requireNonNull(storageType, "storageType");
    defaultValue = normalize(defaultValue);
    comment = normalize(comment);
  }

  private static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
