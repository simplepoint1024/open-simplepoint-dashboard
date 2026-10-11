package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.Objects;

/**
 * Canonical logical value.
 *
 * <p>Dates and times use ISO-8601. DATETIME is a UTC instant. DECIMAL uses a plain decimal
 * string, JSON uses canonical JSON, and BINARY uses unpadded Base64.</p>
 */
public record LogicalValue(LogicalValueKind kind, String canonicalValue) {

  /** Enforces the presence or absence of a canonical payload for the value kind. */
  public LogicalValue {
    Objects.requireNonNull(kind, "kind");
    if (kind == LogicalValueKind.NULL && canonicalValue != null) {
      throw new IllegalArgumentException("NULL values cannot carry a canonical value");
    }
    if (kind != LogicalValueKind.NULL && canonicalValue == null) {
      throw new IllegalArgumentException(kind + " values require a canonical value");
    }
  }

  /** Creates the single canonical null representation. */
  public static LogicalValue nullValue() {
    return new LogicalValue(LogicalValueKind.NULL, null);
  }
}
