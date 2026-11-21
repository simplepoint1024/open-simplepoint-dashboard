package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;
import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlWriteOwnership.unavailable;

import java.sql.Connection;
import java.sql.SQLException;
import org.simplepoint.data.ownership.MysqlTriggerMetadataAccess;

/**
 * Direct DML routing and expression metadata, rechecked on the executing connection. DDL event
 * triggers and source-expression resolution are checked separately.
 */
final class FederationNativeTableEffects {
  private FederationNativeTableEffects() {}

  static void verify(Connection connection, boolean postgres, FederationNativeSqlObjects.Use use)
      throws SQLException {
    if (use.kind() == null || "NAMESPACE".equals(use.kind())) {
      return;
    }
    if (postgres) {
      postgres(connection, use);
      FederationPostgresExpressionEffects.verify(connection, use);
    } else {
      mysql(connection, use);
      FederationMysqlExpressionEffects.verify(connection, use);
    }
    FederationNativeForeignKeyEffects.verify(connection, postgres, use);
  }

  private static void postgres(Connection connection, FederationNativeSqlObjects.Use use)
      throws SQLException {
    // Views/rules, foreign tables and inheritance can route a single named table elsewhere.
    if (!"r".equals(use.kind()) && !(use.kind().equals("m") && !use.write())) {
      throw unsupported();
    }
    String sql =
        "SELECT c.relrowsecurity OR c.relhasrules OR c.relispartition OR NOT EXISTS (SELECT 1"
            + " FROM pg_catalog.pg_am a WHERE a.oid=c.relam AND a.amname='heap' AND a.amtype='t')"
            + " OR EXISTS (SELECT 1 FROM"
            + " pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid), EXISTS"
            + " (SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid=c.oid AND NOT"
            + " t.tgisinternal) FROM pg_catalog.pg_class c WHERE c.oid=CAST(? AS pg_catalog.oid)";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, use.identity());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw unavailable(null);
        }
        if (rows.getBoolean(1) || use.write() && rows.getBoolean(2)) {
          throw unsupported();
        }
        if (rows.next()) {
          throw unavailable(null);
        }
      }
    }
  }

  private static void mysql(Connection connection, FederationNativeSqlObjects.Use use)
      throws SQLException {
    if (!"BASE TABLE".equals(use.kind())) {
      throw unsupported();
    }
    // BASE TABLE also includes remote, routing and nontransactional storage engines. Check
    // dependencies too: a DML source must not silently invoke an unverified storage adapter.
    String engineSql =
        "SELECT ENGINE FROM information_schema.TABLES WHERE"
            + " BINARY TABLE_SCHEMA=? AND BINARY TABLE_NAME=?";
    try (var statement = connection.prepareStatement(engineSql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, use.namespace());
      statement.setString(2, use.table());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw unavailable(null);
        }
        if (!"InnoDB".equals(rows.getString(1))) {
          throw unsupported();
        }
        if (rows.next()) {
          throw unavailable(null);
        }
      }
    }
    if (!use.write()) {
      return;
    }
    MysqlTriggerMetadataAccess.require(connection, use.namespace(), use.table());
    String sql =
        "SELECT 1 FROM information_schema.TRIGGERS WHERE"
            + " BINARY EVENT_OBJECT_SCHEMA=? AND BINARY EVENT_OBJECT_TABLE=? LIMIT 1";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, use.namespace());
      statement.setString(2, use.table());
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw unsupported();
        }
      }
    }
  }
}
