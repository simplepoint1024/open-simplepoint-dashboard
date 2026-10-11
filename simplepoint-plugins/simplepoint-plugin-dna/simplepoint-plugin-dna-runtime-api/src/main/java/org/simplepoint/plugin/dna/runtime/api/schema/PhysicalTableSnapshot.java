package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.Comparator;
import java.util.List;

/** Canonical structure snapshot read from a target physical table. */
public record PhysicalTableSnapshot(
    boolean exists,
    List<PhysicalColumnSnapshot> columns,
    List<PhysicalIndexSnapshot> indexes,
    String tableComment,
    String checksum
) {

  /** Canonicalizes collection order and validates the checksum. */
  public PhysicalTableSnapshot {
    columns = columns == null
        ? List.of()
        : columns.stream().sorted(Comparator.comparing(PhysicalColumnSnapshot::name)).toList();
    indexes = indexes == null
        ? List.of()
        : indexes.stream().sorted(Comparator.comparing(PhysicalIndexSnapshot::name)).toList();
    tableComment = tableComment == null || tableComment.isBlank() ? null : tableComment.trim();
    if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Snapshot checksum must be a SHA-256 value");
    }
    if (!exists && (!columns.isEmpty() || !indexes.isEmpty() || tableComment != null)) {
      throw new IllegalArgumentException("A missing table cannot contain structure metadata");
    }
  }
}
