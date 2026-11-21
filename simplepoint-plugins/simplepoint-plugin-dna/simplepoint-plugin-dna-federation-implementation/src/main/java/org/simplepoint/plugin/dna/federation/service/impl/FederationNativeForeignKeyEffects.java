package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.sql.Connection;
import java.sql.SQLException;

/** Rejects unbounded cascading writes; incomplete privilege-filtered JDBC metadata is not proof. */
final class FederationNativeForeignKeyEffects {
  private FederationNativeForeignKeyEffects() {}

  static void verify(Connection connection, boolean postgres, FederationNativeSqlObjects.Use use)
      throws SQLException {
    if (!use.write()) {
      return;
    }
    String sql =
        postgres
            ? "SELECT 1 FROM pg_catalog.pg_constraint WHERE contype='f'"
                + " AND confrelid=CAST(? AS pg_catalog.oid)"
                + " AND (confdeltype NOT IN ('a','r') OR confupdtype NOT IN ('a','r')) LIMIT 1"
            : "SELECT 1 FROM information_schema.INNODB_FOREIGN WHERE BINARY REF_NAME=?"
                + " AND TYPE NOT IN (0,16,32,48) LIMIT 1";
    // MySQL enforces PROCESS for this complete InnoDB directory. REFERENTIAL_CONSTRAINTS and
    // getExportedKeys can hide an ungranted child even though its cascade still executes.
    // InnoDB TYPE is a bitset: only RESTRICT / NO ACTION combinations are admitted.
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      statement.setString(1, postgres ? use.identity() : use.namespace() + "/" + use.table());
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw unsupported();
        }
      }
    }
  }
}
