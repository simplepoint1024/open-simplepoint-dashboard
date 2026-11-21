package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.impl.FederationSqlStatementBoundary.unsupported;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Native DDL routing/trigger admission; expression and type effects are separate checks. */
final class FederationNativeDdlEffects {
  private FederationNativeDdlEffects() {}

  static void verify(
      Connection connection, boolean postgres, List<FederationNativeSqlObjects.Use> uses)
      throws SQLException {
    if (postgres) {
      // Event triggers are database-wide, not attached to the named table. Do not infer safety
      // from command tags or replication mode; a later setting must not enable a hidden writer.
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        try (var rows =
            statement.executeQuery(
                "SELECT 1 FROM pg_catalog.pg_event_trigger"
                    + " WHERE evtenabled<>'D' AND evtevent<>'login' LIMIT 1")) {
          if (rows.next()) {
            throw unsupported();
          }
        }
      }
    }
    for (var use : uses) {
      if (postgres && ("i".equals(use.kind()) || "I".equals(use.kind()))) {
        // The resolver also includes this index's actual owning table, checked below.
        continue;
      }
      if (use.write() && ("v".equals(use.kind()) || "VIEW".equals(use.kind()))) {
        // Changing/dropping a view definition does not execute its query. Any read dependency
        // still passes table admission; DML through views remains unsupported.
        continue;
      }
      FederationNativeTableEffects.verify(connection, postgres, use);
    }
  }
}
