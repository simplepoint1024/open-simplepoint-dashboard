package org.simplepoint.data.ownership;

import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.DIRECTORY_INVALID;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable physical protocol schema; it is separate from any business module's JPA tables. */
final class OwnershipSchema {
  static final int VERSION = 1;
  static final String HEADER = "namespace";
  private static final String MARKER = "simplepoint-physical-ownership:v1:";
  private static final Map<String, Column> COLUMNS =
      Map.of(
          "entry_key", new Column(Types.VARCHAR, 64, false),
          "namespace_id", new Column(Types.VARCHAR, 36, false),
          "contract_version", new Column(Types.INTEGER, 0, false),
          "schema_checksum", new Column(Types.VARCHAR, 64, false),
          "object_name", new Column(Types.VARCHAR, 255, true),
          "owner_module", new Column(Types.VARCHAR, 64, true),
          "owner_tenant", new Column(Types.VARCHAR, 255, true),
          "owner_resource", new Column(Types.VARCHAR, 128, true),
          "created_at_ms", new Column(Types.BIGINT, 0, false));

  private OwnershipSchema() {}

  static String template(PhysicalNamespace namespace) {
    String path =
        "/org/simplepoint/data/ownership/v1/"
            + (namespace.postgres ? "postgresql" : "mysql")
            + ".sql";
    try (var stream = OwnershipSchema.class.getResourceAsStream(path)) {
      if (stream == null) {
        throw new IllegalStateException("Physical ownership installation asset is missing");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Physical ownership installation asset is unreadable", failure);
    }
  }

  static String checksum(PhysicalNamespace namespace) {
    return PhysicalNamespace.digest(template(namespace));
  }

  static String marker(UUID namespaceId) {
    return MARKER + namespaceId;
  }

  static UUID installedIdentity(Connection connection, PhysicalNamespace namespace)
      throws SQLException {
    String sql =
        namespace.postgres
            ? "SELECT pg_catalog.obj_description(c.oid, 'pg_class'), c.relkind, c.relpersistence,"
                + " c.relrowsecurity, c.relforcerowsecurity, c.relispartition, c.relhasrules,"
                + " EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR"
                + " i.inhparent=c.oid), EXISTS (SELECT 1 FROM pg_catalog.pg_am a WHERE"
                + " a.oid=c.relam AND a.amname='heap' AND a.amtype='t')"
                + " FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n"
                + " ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?"
            : "SELECT TABLE_COMMENT, TABLE_TYPE, ENGINE FROM information_schema.TABLES "
                + "WHERE BINARY TABLE_SCHEMA=? AND BINARY TABLE_NAME=?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, namespace.name);
      statement.setString(2, PhysicalNamespace.DIRECTORY);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        String comment = rows.getString(1);
        boolean type =
            namespace.postgres
                ? "r".equals(rows.getString(2))
                    && "p".equals(rows.getString(3))
                    && !rows.getBoolean(4)
                    && !rows.getBoolean(5)
                    && !rows.getBoolean(6)
                    && !rows.getBoolean(7)
                    && !rows.getBoolean(8)
                    && rows.getBoolean(9)
                : "BASE TABLE".equals(rows.getString(2))
                    && "InnoDB".equalsIgnoreCase(rows.getString(3));
        if (!type || comment == null || !comment.startsWith(MARKER) || rows.next()) {
          throw invalid();
        }
        try {
          UUID id = UUID.fromString(comment.substring(MARKER.length()));
          if (!comment.equals(marker(id))) {
            throw invalid();
          }
          return id;
        } catch (IllegalArgumentException failure) {
          throw invalid();
        }
      }
    }
  }

  static void verify(Connection connection, PhysicalNamespace namespace) throws SQLException {
    if (!namespace.postgres) {
      MysqlTriggerMetadataAccess.require(connection, namespace.name, PhysicalNamespace.DIRECTORY);
    }
    DatabaseMetaData metadata = connection.getMetaData();
    String table = escape(metadata, PhysicalNamespace.DIRECTORY);
    String schema =
        namespace.metadataSchema() == null ? null : escape(metadata, namespace.metadataSchema());
    Map<String, Column> actual = new HashMap<>();
    try (var columns = metadata.getColumns(namespace.metadataCatalog(), schema, table, null)) {
      while (columns.next()) {
        String name = columns.getString("COLUMN_NAME");
        int type = columns.getInt("DATA_TYPE");
        int nullable = columns.getInt("NULLABLE");
        if (!PhysicalNamespace.DIRECTORY.equals(columns.getString("TABLE_NAME"))
            || nullable != DatabaseMetaData.columnNullable
                && nullable != DatabaseMetaData.columnNoNulls
            || columns.getString("COLUMN_DEF") != null
            || "YES".equals(columns.getString("IS_AUTOINCREMENT"))
            || "YES".equals(columns.getString("IS_GENERATEDCOLUMN"))) {
          throw invalid();
        }
        var column =
            new Column(
                type,
                type == Types.VARCHAR ? columns.getInt("COLUMN_SIZE") : 0,
                nullable == DatabaseMetaData.columnNullable);
        if (actual.putIfAbsent(name, column) != null || actual.size() > COLUMNS.size()) {
          throw invalid();
        }
      }
    }
    if (!actual.equals(COLUMNS)) {
      throw invalid();
    }
    Set<String> primary = new HashSet<>();
    try (var keys =
        metadata.getPrimaryKeys(
            namespace.metadataCatalog(), namespace.metadataSchema(), PhysicalNamespace.DIRECTORY)) {
      while (keys.next()) {
        if (!primary.add(keys.getString("COLUMN_NAME"))) {
          throw invalid();
        }
      }
    }
    if (!primary.equals(Set.of("entry_key"))) {
      throw invalid();
    }
    try (var keys =
        metadata.getImportedKeys(
            namespace.metadataCatalog(), namespace.metadataSchema(), PhysicalNamespace.DIRECTORY)) {
      if (keys.next()) {
        throw invalid();
      }
    }
    try (var keys =
        metadata.getExportedKeys(
            namespace.metadataCatalog(), namespace.metadataSchema(), PhysicalNamespace.DIRECTORY)) {
      if (keys.next()) {
        throw invalid();
      }
    }
    String triggers =
        namespace.postgres
            ? "SELECT 1 FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=? AND NOT t.tgisinternal LIMIT 1"
            : "SELECT 1 FROM information_schema.TRIGGERS WHERE BINARY EVENT_OBJECT_SCHEMA=? "
                + "AND BINARY EVENT_OBJECT_TABLE=? LIMIT 1";
    try (var statement = connection.prepareStatement(triggers)) {
      statement.setQueryTimeout(10);
      statement.setString(1, namespace.name);
      statement.setString(2, PhysicalNamespace.DIRECTORY);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw invalid();
        }
      }
    }
  }

  static boolean objectExists(Connection connection, PhysicalNamespace namespace, String name)
      throws SQLException {
    // A view, sequence or another relation with the same name also prevents automatic adoption.
    String sql =
        namespace.postgres
            ? "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON"
                + " n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?"
            : "SELECT 1 FROM information_schema.TABLES WHERE BINARY TABLE_SCHEMA=? AND "
                + (namespace.tableCase == 0 ? "BINARY TABLE_NAME=?" : "BINARY LOWER(TABLE_NAME)=?");
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, namespace.name);
      statement.setString(2, name);
      try (var rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static String escape(DatabaseMetaData metadata, String value) throws SQLException {
    String escape = metadata.getSearchStringEscape();
    if (escape == null || escape.isEmpty()) {
      throw invalid();
    }
    return value
        .replace(escape, escape + escape)
        .replace("_", escape + "_")
        .replace("%", escape + "%");
  }

  static PhysicalOwnershipException invalid() {
    return new PhysicalOwnershipException(DIRECTORY_INVALID);
  }

  private record Column(int type, int length, boolean nullable) {}
}
