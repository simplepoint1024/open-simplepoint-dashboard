package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.Comparator;
import java.util.List;

/** Canonical structure snapshot read from a target physical table. */
public record PhysicalTableSnapshot(
    boolean exists,
    List<PhysicalColumnSnapshot> columns,
    List<PhysicalIndexSnapshot> indexes,
    String tableComment,
    String checksum,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    List<PhysicalForeignKeySnapshot> foreignKeys
) {

  /** Retains the original serialization and source contract for tables without references. */
  public PhysicalTableSnapshot(boolean exists, List<PhysicalColumnSnapshot> columns,
      List<PhysicalIndexSnapshot> indexes, String tableComment, String checksum) {
    this(exists, columns, indexes, tableComment, checksum, List.of());
  }

  /** Canonicalizes collection order and validates the checksum. */
  public PhysicalTableSnapshot {
    columns = columns == null
        ? List.of()
        : columns.stream().sorted(Comparator.comparing(PhysicalColumnSnapshot::name)).toList();
    indexes = indexes == null
        ? List.of()
        : indexes.stream().sorted(Comparator.comparing(PhysicalIndexSnapshot::name)).toList();
    tableComment = tableComment == null || tableComment.isBlank() ? null : tableComment.trim();
    foreignKeys = foreignKeys == null ? List.of()
        : foreignKeys.stream().sorted(Comparator.comparing(PhysicalForeignKeySnapshot::name)).toList();
    if (foreignKeys.stream().map(PhysicalForeignKeySnapshot::name).distinct().count() != foreignKeys.size()) {
      throw new IllegalArgumentException("Foreign-key names must be unique within a table");
    }
    if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Snapshot checksum must be a SHA-256 value");
    }
    if (!exists && (!columns.isEmpty() || !indexes.isEmpty() || !foreignKeys.isEmpty() || tableComment != null)) {
      throw new IllegalArgumentException("A missing table cannot contain structure metadata");
    }
  }
}
