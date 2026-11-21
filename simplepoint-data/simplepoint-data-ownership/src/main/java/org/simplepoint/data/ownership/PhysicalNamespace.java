package org.simplepoint.data.ownership;

import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.NAMESPACE_UNAVAILABLE;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Coordinates resolved on the executing connection, never from a configured datasource ID or URL.
 */
final class PhysicalNamespace {
  static final String DIRECTORY = "simpoint_physical_object_owners_v1";
  final boolean postgres;
  final String name;
  final String catalog;
  final int tableCase;

  private PhysicalNamespace(boolean postgres, String name, String catalog, int tableCase) {
    this.postgres = postgres;
    this.name = name;
    this.catalog = catalog;
    this.tableCase = tableCase;
  }

  static PhysicalNamespace resolve(Connection connection, String requestedNamespace)
      throws SQLException {
    var rules = PhysicalIdentifierRules.on(connection);
    boolean postgres = rules.postgres();
    String catalog = connection.getCatalog();
    String name =
        requestedNamespace == null
            ? (postgres ? connection.getSchema() : catalog)
            : requestedNamespace;
    if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
      throw new PhysicalOwnershipException(NAMESPACE_UNAVAILABLE);
    }
    String sql =
        postgres
            ? "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname = ?"
            : "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE BINARY SCHEMA_NAME = ?";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, name);
      try (var rows = statement.executeQuery()) {
        if (!rows.next() || !name.equals(rows.getString(1)) || rows.next()) {
          throw new PhysicalOwnershipException(NAMESPACE_UNAVAILABLE);
        }
      }
    }
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.equals("information_schema")
        || postgres && lower.startsWith("pg_")
        || !postgres
            && (lower.equals("mysql")
                || lower.equals("performance_schema")
                || lower.equals("sys"))) {
      throw new PhysicalOwnershipException(NAMESPACE_UNAVAILABLE);
    }
    return new PhysicalNamespace(postgres, name, catalog, rules.tableCase());
  }

  String canonical(Connection connection, String table, boolean quoted) throws SQLException {
    return PhysicalIdentifierRules.canonical(connection, postgres, tableCase, table, quoted);
  }

  String quote(String value) {
    String delimiter = postgres ? "\"" : "`";
    return delimiter + value.replace(delimiter, delimiter + delimiter) + delimiter;
  }

  String directory() {
    return quote(name) + "." + quote(DIRECTORY);
  }

  String lockKey() {
    // PostgreSQL advisory locks are database-local; MySQL named locks are server-local.
    return digest("simplepoint-physical-namespace-v1:" + name.length() + ":" + name);
  }

  String metadataCatalog() {
    return postgres ? catalog : name;
  }

  String metadataSchema() {
    return postgres ? name : null;
  }

  static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }
}
