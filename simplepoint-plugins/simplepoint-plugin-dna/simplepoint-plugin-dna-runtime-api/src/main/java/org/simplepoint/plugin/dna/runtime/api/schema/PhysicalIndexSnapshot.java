package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.List;
import java.util.Locale;

/** Normalized primary or secondary index structure. */
public record PhysicalIndexSnapshot(
    String name,
    List<String> columns,
    boolean unique,
    boolean primary
) {

  /** Validates and normalizes ordered index columns. */
  public PhysicalIndexSnapshot {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Index name is required");
    }
    name = name.trim().toLowerCase(Locale.ROOT);
    columns = columns == null
        ? List.of()
        : columns.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    if (columns.isEmpty()) {
      throw new IllegalArgumentException("Index columns are required");
    }
    if (primary && !unique) {
      throw new IllegalArgumentException("A primary index must be unique");
    }
  }
}
