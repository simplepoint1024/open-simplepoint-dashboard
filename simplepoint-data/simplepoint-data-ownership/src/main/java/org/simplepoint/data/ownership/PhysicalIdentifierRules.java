package org.simplepoint.data.ownership;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Native identifier rules on one executing connection; these do not authorize object access. */
public final class PhysicalIdentifierRules {
  private final Connection connection;
  private final boolean postgres;
  private final int tableCase;

  private PhysicalIdentifierRules(Connection connection, boolean postgres, int tableCase) {
    this.connection = connection;
    this.postgres = postgres;
    this.tableCase = tableCase;
  }

  /** Captures the server's identifier rules without installing or changing any database state. */
  public static PhysicalIdentifierRules on(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    String product = connection.getMetaData().getDatabaseProductName();
    boolean postgres = "PostgreSQL".equals(product);
    if (!postgres && !"MySQL".equals(product)) {
      throw new PhysicalOwnershipException(PhysicalOwnershipException.Code.UNSUPPORTED_DATABASE);
    }
    int tableCase = 0;
    if (!postgres) {
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        try (var rows = statement.executeQuery("SELECT @@lower_case_table_names")) {
          if (!rows.next()) {
            throw new PhysicalOwnershipException(
                PhysicalOwnershipException.Code.UNSUPPORTED_DATABASE);
          }
          tableCase = rows.getInt(1);
          if (rows.wasNull() || tableCase < 0 || tableCase > 2 || rows.next()) {
            throw new PhysicalOwnershipException(
                PhysicalOwnershipException.Code.UNSUPPORTED_DATABASE);
          }
        }
      }
    }
    return new PhysicalIdentifierRules(connection, postgres, tableCase);
  }

  /** Whether this connection uses PostgreSQL's native name and catalog rules. */
  public boolean postgres() {
    return postgres;
  }

  /** Validates one identifier and preserves the server's quoted and Unicode semantics. */
  public String canonical(String name, boolean quoted) throws SQLException {
    return canonical(connection, postgres, tableCase, name, quoted);
  }

  static String canonical(
      Connection connection, boolean postgres, int tableCase, String table, boolean quoted)
      throws SQLException {
    PhysicalObjectOwner.required(table, 255);
    if (postgres) {
      // parse_ident follows PostgreSQL's lexer, including non-ASCII case rules. It does not
      // truncate identifiers: reject overlong names before PostgreSQL could alias them on CREATE.
      String sql =
          "SELECT names[1], pg_catalog.cardinality(names),"
              + " pg_catalog.octet_length(pg_catalog.convert_to(names[1],"
              + " pg_catalog.current_setting('server_encoding'))),"
              + " CAST(pg_catalog.current_setting('max_identifier_length') AS integer) FROM (SELECT"
              + " pg_catalog.parse_ident(?, true) AS names) parsed";
      try (var statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(10);
        statement.setString(1, quoted ? quote(table, postgres) : table);
        try (var rows = statement.executeQuery()) {
          if (!rows.next() || rows.getInt(2) != 1 || rows.getInt(3) > rows.getInt(4)) {
            throw new PhysicalOwnershipException(
                PhysicalOwnershipException.Code.INVALID_OBJECT_NAME);
          }
          String value = rows.getString(1);
          if (value == null || rows.next()) {
            throw new PhysicalOwnershipException(
                PhysicalOwnershipException.Code.INVALID_OBJECT_NAME);
          }
          return value;
        }
      }
    }
    if (table.codePointCount(0, table.length()) > 64
        || table.codePoints().anyMatch(value -> value > 0xffff)
        || table.endsWith(" ")
        || table.indexOf('.') >= 0
        || table.indexOf('/') >= 0
        || table.indexOf('\\') >= 0) {
      throw new PhysicalOwnershipException(PhysicalOwnershipException.Code.INVALID_OBJECT_NAME);
    }
    if (tableCase == 0) {
      return table;
    }
    try (var statement = connection.prepareStatement("SELECT LOWER(CONVERT(? USING utf8mb3))")) {
      statement.setQueryTimeout(10);
      statement.setString(1, table);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new PhysicalOwnershipException(PhysicalOwnershipException.Code.INVALID_OBJECT_NAME);
        }
        String value = rows.getString(1);
        if (value == null || rows.next()) {
          throw new PhysicalOwnershipException(PhysicalOwnershipException.Code.INVALID_OBJECT_NAME);
        }
        return value;
      }
    }
  }

  /** Quotes an already resolved identifier for this connection's SQL dialect. */
  public String quote(String name) {
    return quote(name, postgres);
  }

  private static String quote(String value, boolean postgres) {
    String delimiter = postgres ? "\"" : "`";
    return delimiter + value.replace(delimiter, delimiter + delimiter) + delimiter;
  }

  int tableCase() {
    return tableCase;
  }
}
