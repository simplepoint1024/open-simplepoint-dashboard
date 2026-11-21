package org.simplepoint.data.ownership;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

/** Proves complete table metadata access using the connection's active, effective grants. */
public final class MysqlTableMetadataAccess {
  private MysqlTableMetadataAccess() {}

  /** Column metadata must not be a partial projection of column-level grants. */
  public static void requireSelect(Connection connection, String database, String table)
      throws SQLException {
    prove(
        connection,
        database,
        java.util.Objects.requireNonNull(table, "table"),
        MysqlPrivilegeGrants.Privilege.SELECT);
  }

  static void requireTrigger(Connection connection, String database, String table)
      throws SQLException {
    prove(connection, database, table, MysqlPrivilegeGrants.Privilege.TRIGGER);
  }

  private static void prove(
      Connection connection,
      String database,
      String table,
      MysqlPrivilegeGrants.Privilege privilege)
      throws SQLException {
    var roles = new ArrayList<String>();
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      statement.setMaxRows(65);
      try (var rows =
          statement.executeQuery(
              "SELECT ROLE_NAME, ROLE_HOST FROM information_schema.ENABLED_ROLES")) {
        while (rows.next()) {
          if (roles.size() >= 64) {
            throw unavailable();
          }
          roles.add(quoteRole(rows.getString(1)) + "@" + quoteRole(rows.getString(2)));
        }
      }
    }
    var grants = new ArrayList<String>();
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      statement.setMaxRows(513);
      String sql =
          "SHOW GRANTS FOR CURRENT_USER"
              + (roles.isEmpty() ? "" : " USING " + String.join(",", roles));
      try (var rows = statement.executeQuery(sql)) {
        while (rows.next()) {
          if (grants.size() >= 512) {
            throw unavailable();
          }
          grants.add(rows.getString(1));
        }
      }
    }
    boolean literalDatabase;
    int tableCase;
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var rows =
          statement.executeQuery("SELECT @@partial_revokes, @@lower_case_table_names")) {
        if (!rows.next()) {
          throw unavailable();
        }
        literalDatabase = rows.getBoolean(1);
        if (rows.wasNull()) {
          throw unavailable();
        }
        tableCase = rows.getInt(2);
        if (rows.wasNull() || tableCase < 0 || tableCase > 2 || rows.next()) {
          throw unavailable();
        }
      }
    }
    var rules = PhysicalIdentifierRules.on(connection);
    boolean global = false;
    boolean revoked = false;
    boolean explicit = false;
    boolean databaseGrant = false;
    boolean conflictingDatabaseGrant = false;
    for (var grant : MysqlPrivilegeGrants.forPrivilege(grants, privilege)) {
      if (grant.database() == null) {
        if (grant.revoked()) {
          revoked = true;
        } else {
          global = true;
        }
      } else if (grant.table() != null) {
        if (table != null
            && rules.canonical(grant.database(), true).equals(rules.canonical(database, true))
            && rules.canonical(grant.table(), true).equals(rules.canonical(table, true))) {
          explicit = grant.granted();
        }
      } else if (matchesDatabase(
          connection,
          rules,
          database,
          grant.database(),
          literalDatabase || grant.revoked(),
          tableCase)) {
        if (grant.revoked()) {
          revoked = true;
        } else {
          if (grant.granted()) {
            databaseGrant = true;
          } else {
            conflictingDatabaseGrant = true;
          }
        }
      }
    }
    // MySQL selects a matching database ACL row, not the union of all matching patterns.
    // Conflicting matches need an unambiguous table/global grant; never guess ACL precedence.
    if (!explicit && !(global && !revoked) && !(databaseGrant && !conflictingDatabaseGrant)) {
      throw unavailable();
    }
  }

  private static boolean matchesDatabase(
      Connection connection,
      PhysicalIdentifierRules rules,
      String database,
      String granted,
      boolean literal,
      int tableCase)
      throws SQLException {
    if (literal) {
      return rules.canonical(database, true).equals(rules.canonical(granted, true));
    }
    // The server evaluates its own database privilege pattern and character/case semantics.
    String operand =
        tableCase == 0 ? "CAST(? AS BINARY)" : "CAST(LOWER(CONVERT(? USING utf8mb3)) AS BINARY)";
    try (var statement =
        connection.prepareStatement("SELECT " + operand + " LIKE " + operand + " ESCAPE 0x5c")) {
      statement.setQueryTimeout(10);
      statement.setString(1, database);
      statement.setString(2, granted);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw unavailable();
        }
        boolean matches = rows.getBoolean(1);
        if (rows.wasNull() || rows.next()) {
          throw unavailable();
        }
        return matches;
      }
    }
  }

  private static String quoteRole(String value) throws PhysicalOwnershipException {
    if (value == null
        || value.length() > 255
        || value.indexOf('\0') >= 0
        || value.indexOf('\\') >= 0) {
      throw unavailable();
    }
    return "`" + value.replace("`", "``") + "`";
  }

  static PhysicalOwnershipException unavailable() {
    return new PhysicalOwnershipException(PhysicalOwnershipException.Code.METADATA_UNAVAILABLE);
  }
}
