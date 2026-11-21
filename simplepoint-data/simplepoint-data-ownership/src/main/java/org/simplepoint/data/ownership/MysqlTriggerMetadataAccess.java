package org.simplepoint.data.ownership;

import java.sql.Connection;
import java.sql.SQLException;

/** Proves complete TRIGGER visibility without granting privileges or changing active roles. */
public final class MysqlTriggerMetadataAccess {
  private MysqlTriggerMetadataAccess() {}

  /** Requires complete trigger visibility for a single table. */
  public static void require(Connection connection, String database, String table)
      throws SQLException {
    MysqlTableMetadataAccess.requireTrigger(
        connection, database, java.util.Objects.requireNonNull(table, "table"));
  }

  /** Requires database-wide metadata authority; an isolated table grant is insufficient. */
  public static void requireNamespace(Connection connection, String database) throws SQLException {
    MysqlTableMetadataAccess.requireTrigger(connection, database, null);
  }

  static PhysicalOwnershipException unavailable() {
    return new PhysicalOwnershipException(PhysicalOwnershipException.Code.METADATA_UNAVAILABLE);
  }
}
