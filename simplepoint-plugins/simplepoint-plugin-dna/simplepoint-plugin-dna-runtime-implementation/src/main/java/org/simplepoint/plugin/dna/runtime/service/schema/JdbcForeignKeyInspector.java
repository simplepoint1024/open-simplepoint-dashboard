package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Action;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Deferrability;

/** Strict JDBC inspection separated from planning so drift checks also cover unmanaged constraints. */
public final class JdbcForeignKeyInspector {
  private JdbcForeignKeyInspector() {}

  public static List<PhysicalForeignKeySnapshot> inspect(DatabaseMetaData metadata,
      String catalog, String schema, String table) throws SQLException {
    try (var rows = metadata.getImportedKeys(catalog, schema, table)) {
      return read(rows, catalog, schema, table, false).stream().map(ForeignKeyLink::constraint).toList();
    }
  }

  /** Incoming physical references, including hidden, offline and retained source records. */
  public static List<ForeignKeyLink> incoming(DatabaseMetaData metadata,
      String catalog, String schema, String table) throws SQLException {
    try (var rows = metadata.getExportedKeys(catalog, schema, table)) {
      return read(rows, catalog, schema, table, true);
    }
  }

  /** Storage metadata, never an authorization or a business relationship discovery contract. */
  public record ForeignKeyLink(String sourceCatalog, String sourceSchema, String sourceTable,
      PhysicalForeignKeySnapshot constraint) {}

  private static List<ForeignKeyLink> read(ResultSet rows, String catalog, String schema,
      String table, boolean incoming) throws SQLException {
    // Constraint names need not be unique across source tables (notably PostgreSQL).
    Map<SourceKey, ForeignKey> keys = new java.util.LinkedHashMap<>();
    String prefix = incoming ? "PKTABLE_" : "FKTABLE_";
    int inspected = 0;
    while (rows.next()) {
      if (++inspected > 8192) throw new SQLException("Foreign-key metadata exceeds the inspection budget");
      if (!table.equals(rows.getString(prefix + "NAME"))
          || !Objects.equals(namespace(catalog), namespace(rows.getString(prefix + "CAT")))
          || !Objects.equals(namespace(schema), namespace(rows.getString(prefix + "SCHEM")))) continue;
      var source = new SourceKey(namespace(rows.getString("FKTABLE_CAT")),
          namespace(rows.getString("FKTABLE_SCHEM")), required(rows, "FKTABLE_NAME"), required(rows, "FK_NAME"));
      var target = new Target(namespace(rows.getString("PKTABLE_CAT")),
          namespace(rows.getString("PKTABLE_SCHEM")), required(rows, "PKTABLE_NAME"),
          action(number(rows, "UPDATE_RULE")), action(number(rows, "DELETE_RULE")),
          deferrability(number(rows, "DEFERRABILITY")));
      ForeignKey key = keys.computeIfAbsent(source, ignored -> new ForeignKey(target));
      int sequence = number(rows, "KEY_SEQ");
      var pair = new ColumnPair(required(rows, "FKCOLUMN_NAME"), required(rows, "PKCOLUMN_NAME"));
      var previous = key.columns.putIfAbsent(sequence, pair);
      // Connector/J 9.5 getExportedKeys joins every target unique key and can repeat a column
      // pair with different PK_NAME values (MySQL bug #104641). PK_NAME is not our identity;
      // collapse only identical incoming rows, never conflicting mappings/actions or missing positions.
      if (!key.target.equals(target) || sequence < 1
          || (previous != null && (!incoming || !previous.equals(pair)))) {
        throw new SQLException("Inconsistent foreign-key metadata");
      }
    }
    List<ForeignKeyLink> result = new ArrayList<>();
    for (var entry : keys.entrySet()) {
      var key = entry.getValue();
      if (key.columns.lastKey() != key.columns.size()) {
        throw new SQLException("Foreign-key metadata has missing column positions");
      }
      var target = key.target;
      var source = entry.getKey();
      try {
        result.add(new ForeignKeyLink(source.catalog(), source.schema(), source.table(),
            new PhysicalForeignKeySnapshot(source.name(),
                key.columns.values().stream().map(ColumnPair::source).toList(),
                target.catalog(), target.schema(), target.table(),
                key.columns.values().stream().map(ColumnPair::target).toList(),
                target.update(), target.delete(), target.deferrability())));
      } catch (IllegalArgumentException | NullPointerException failure) {
        throw new SQLException("Invalid foreign-key metadata", failure);
      }
    }
    var nullable = java.util.Comparator.nullsFirst(java.util.Comparator.<String>naturalOrder());
    result.sort(java.util.Comparator.comparing(ForeignKeyLink::sourceCatalog, nullable)
        .thenComparing(ForeignKeyLink::sourceSchema, nullable).thenComparing(ForeignKeyLink::sourceTable)
        .thenComparing(link -> link.constraint().name()));
    return List.copyOf(result);
  }

  private record SourceKey(String catalog, String schema, String table, String name) {}

  private static Action action(int value) throws SQLException {
    return switch (value) {
      case DatabaseMetaData.importedKeyNoAction -> Action.NO_ACTION;
      case DatabaseMetaData.importedKeyRestrict -> Action.RESTRICT;
      case DatabaseMetaData.importedKeyCascade -> Action.CASCADE;
      case DatabaseMetaData.importedKeySetNull -> Action.SET_NULL;
      case DatabaseMetaData.importedKeySetDefault -> Action.SET_DEFAULT;
      default -> throw new SQLException("Unsupported foreign-key referential action");
    };
  }

  private static Deferrability deferrability(int value) throws SQLException {
    return switch (value) {
      case DatabaseMetaData.importedKeyNotDeferrable -> Deferrability.NOT_DEFERRABLE;
      case DatabaseMetaData.importedKeyInitiallyImmediate -> Deferrability.INITIALLY_IMMEDIATE;
      case DatabaseMetaData.importedKeyInitiallyDeferred -> Deferrability.INITIALLY_DEFERRED;
      default -> throw new SQLException("Unsupported foreign-key deferrability");
    };
  }

  private static int number(ResultSet rows, String name) throws SQLException {
    int value = rows.getInt(name);
    if (rows.wasNull()) throw new SQLException("Missing foreign-key metadata value");
    return value;
  }

  private static String required(ResultSet rows, String name) throws SQLException {
    String value = rows.getString(name);
    if (value == null || value.isBlank()) throw new SQLException("Missing foreign-key identifier");
    return value;
  }

  private static String namespace(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record Target(String catalog, String schema, String table,
      Action update, Action delete, Deferrability deferrability) {}
  private record ColumnPair(String source, String target) {}
  private static final class ForeignKey {
    private final Target target;
    private final TreeMap<Integer, ColumnPair> columns = new TreeMap<>();
    private ForeignKey(Target target) { this.target = target; }
  }
}
