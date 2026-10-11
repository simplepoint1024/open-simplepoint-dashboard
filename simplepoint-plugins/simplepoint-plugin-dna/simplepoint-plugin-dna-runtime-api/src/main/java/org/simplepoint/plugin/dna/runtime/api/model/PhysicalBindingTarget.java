package org.simplepoint.plugin.dna.runtime.api.model;

import java.util.Objects;

/** Server-resolved target for one physical model. */
public record PhysicalBindingTarget(
    String dataSourceId,
    String catalogName,
    String schemaName,
    PhysicalIsolationMode isolationMode
) {

  /** Normalizes optional namespace names and validates the configured target. */
  public PhysicalBindingTarget {
    if (dataSourceId == null || dataSourceId.isBlank() || dataSourceId.length() > 64) {
      throw new IllegalArgumentException("dataSourceId must contain 1 to 64 characters");
    }
    dataSourceId = dataSourceId.trim();
    catalogName = normalizeOptional(catalogName, 128, "catalogName");
    schemaName = normalizeOptional(schemaName, 128, "schemaName");
    Objects.requireNonNull(isolationMode, "isolationMode");
  }

  private static String normalizeOptional(
      final String value,
      final int maxLength,
      final String name
  ) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(name + " cannot exceed " + maxLength + " characters");
    }
    return normalized;
  }
}
