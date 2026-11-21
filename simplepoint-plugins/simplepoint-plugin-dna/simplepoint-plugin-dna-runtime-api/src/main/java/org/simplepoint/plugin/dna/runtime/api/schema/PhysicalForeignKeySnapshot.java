package org.simplepoint.plugin.dna.runtime.api.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Ordered physical foreign-key identity, including the referenced namespace and behavior. */
public record PhysicalForeignKeySnapshot(
    String name,
    List<String> columns,
    String targetCatalog,
    String targetSchema,
    String targetTable,
    List<String> targetColumns,
    Action updateAction,
    Action deleteAction,
    Deferrability deferrability) {

  /** Database-neutral JDBC referential actions; inspection must not collapse distinct policies. */
  public enum Action { NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT }

  /** Deferrable constraints do not provide the same statement-level guarantee as immediate ones. */
  public enum Deferrability { NOT_DEFERRABLE, INITIALLY_IMMEDIATE, INITIALLY_DEFERRED }

  /** Keeps column pairing and identifier case intact, including composite tenant references. */
  public PhysicalForeignKeySnapshot {
    name = identifier(name);
    targetCatalog = namespace(targetCatalog);
    targetSchema = namespace(targetSchema);
    targetTable = identifier(targetTable);
    columns = columns(columns);
    targetColumns = columns(targetColumns);
    if (columns.size() != targetColumns.size()) {
      throw new IllegalArgumentException("Foreign-key column pairs must have equal length");
    }
    Objects.requireNonNull(updateAction, "Foreign-key update action is required");
    Objects.requireNonNull(deleteAction, "Foreign-key delete action is required");
    Objects.requireNonNull(deferrability, "Foreign-key deferrability is required");
  }

  private static String identifier(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Foreign-key identifier is required");
    }
    return value;
  }

  private static String namespace(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static List<String> columns(List<String> values) {
    var copy = values == null ? List.<String>of() : List.copyOf(values);
    if (copy.isEmpty() || new HashSet<>(copy).size() != copy.size()) {
      throw new IllegalArgumentException("Foreign-key columns must be nonempty and distinct");
    }
    copy.forEach(PhysicalForeignKeySnapshot::identifier);
    return copy;
  }
}
