package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;
import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlWriteOwnership.unavailable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import org.simplepoint.data.ownership.MysqlTableMetadataAccess;

/** MySQL catalog expressions; literal defaults are values, never SQL source to evaluate. */
final class FederationMysqlExpressionEffects {
  private static final Set<String> TYPES =
      Set.of(
          "tinyint",
          "smallint",
          "mediumint",
          "int",
          "bigint",
          "decimal",
          "float",
          "double",
          "bit",
          "char",
          "varchar",
          "binary",
          "varbinary",
          "tinytext",
          "text",
          "mediumtext",
          "longtext",
          "tinyblob",
          "blob",
          "mediumblob",
          "longblob",
          "date",
          "time",
          "datetime",
          "timestamp",
          "year",
          "json",
          "enum",
          "set");

  private FederationMysqlExpressionEffects() {}

  static void verify(Connection connection, FederationNativeSqlObjects.Use use)
      throws SQLException {
    // COLUMNS filters per-column grants. A nonempty but incomplete result is not sufficient.
    MysqlTableMetadataAccess.requireSelect(connection, use.namespace(), use.table());
    columns(connection, use);
    expressions(
        connection,
        use,
        "SELECT CASE WHEN OCTET_LENGTH(EXPRESSION)<=65536 THEN EXPRESSION END"
            + " FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=?"
            + " AND BINARY TABLE_NAME=? AND EXPRESSION IS NOT NULL");
    if (use.write()) {
      expressions(
          connection,
          use,
          "SELECT CASE WHEN OCTET_LENGTH(c.CHECK_CLAUSE)<=65536 THEN c.CHECK_CLAUSE END FROM"
              + " information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS"
              + " c ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND"
              + " c.CONSTRAINT_NAME=t.CONSTRAINT_NAME WHERE BINARY t.TABLE_SCHEMA=? AND BINARY"
              + " t.TABLE_NAME=? AND t.CONSTRAINT_TYPE='CHECK'");
    }
  }

  private static void columns(Connection connection, FederationNativeSqlObjects.Use use)
      throws SQLException {
    String sql =
        "SELECT DATA_TYPE,EXTRA, CASE WHEN OCTET_LENGTH(COLUMN_DEFAULT)<=65536"
            + " THEN COLUMN_DEFAULT END, CASE WHEN OCTET_LENGTH(GENERATION_EXPRESSION)<=65536"
            + " THEN GENERATION_EXPRESSION END FROM information_schema.COLUMNS"
            + " WHERE BINARY TABLE_SCHEMA=? AND BINARY TABLE_NAME=?";
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, use);
      try (var rows = statement.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          if (++count > 2048 || !TYPES.contains(rows.getString(1))) {
            throw unsupported();
          }
          String extra = rows.getString(2);
          if (extra == null) {
            throw unavailable(null);
          }
          if (use.write() && extra.toUpperCase(Locale.ROOT).contains("DEFAULT_GENERATED")) {
            FederationCatalogExpression.verify(rows.getString(3), false);
          }
          String generated = rows.getString(4);
          if (generated == null || !generated.isEmpty()) {
            FederationCatalogExpression.verify(generated, false);
          }
        }
        if (count == 0) {
          throw unavailable(null);
        }
      }
    }
  }

  private static void expressions(
      Connection connection, FederationNativeSqlObjects.Use use, String sql) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      bind(statement, use);
      try (var rows = statement.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          if (++count > 2048) {
            throw unsupported();
          }
          FederationCatalogExpression.verify(rows.getString(1), false);
        }
      }
    }
  }

  private static void bind(PreparedStatement statement, FederationNativeSqlObjects.Use use)
      throws SQLException {
    statement.setQueryTimeout(10);
    statement.setString(1, use.namespace());
    statement.setString(2, use.table());
  }
}
