package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

/** Effective privilege visibility and real console writes under separately provisioned accounts. */
class FederationMysqlTriggerVisibilityDatabaseTest {
  @Test
  void missingMetadataPrivilegeRejectsInsteadOfAssumingNoTriggers() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        var console = fixture.console("ds", account.source);
        rejected(console, Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
        fixture.execute("GRANT TRIGGER ON " + fixture.name + ".ordinary TO " + account.user);
        assertEquals(
            1,
            console
                .executeUpdate(new SqlConsoleRequest("ds", "INSERT INTO ds.ordinary VALUES (2,7)"))
                .affectedRows());
        try (var connection = fixture.pool.getConnection()) {
          FederationNativeTableEffectsDatabaseTest.trigger(connection, false, fixture.name);
        }
        rejected(console, Code.SQL_WRITE_UNSUPPORTED);
        fixture.execute("REVOKE TRIGGER ON " + fixture.name + ".ordinary FROM " + account.user);
        // The trigger is now hidden, but the admission result must not become successful.
        rejected(console, Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
        fixture.assertUnlocked();
      }
    }
  }

  @Test
  void inactiveRoleDoesNotProveVisibilityAndActiveRoleCanAuthorizeIt() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        fixture.execute("GRANT TRIGGER ON " + fixture.name + ".ordinary TO " + account.role);
        fixture.execute("GRANT " + account.role + " TO " + account.user);
        var console = fixture.console("ds", account.source);
        rejected(console, Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
        fixture.execute("SET DEFAULT ROLE " + account.role + " TO " + account.user);
        assertEquals(
            1,
            console
                .executeUpdate(new SqlConsoleRequest("ds", "INSERT INTO ds.ordinary VALUES (2,7)"))
                .affectedRows());
        try (var connection = fixture.pool.getConnection()) {
          FederationNativeTableEffectsDatabaseTest.trigger(connection, false, fixture.name);
        }
        rejected(console, Code.SQL_WRITE_UNSUPPORTED);
        fixture.execute("REVOKE TRIGGER ON " + fixture.name + ".ordinary FROM " + account.role);
        rejected(console, Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
        fixture.assertUnlocked();
      }
    }
  }

  @Test
  void databaseScopeUsesNativeWildcardOrLiteralMode() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        boolean literal = fixture.scalar("SELECT @@partial_revokes") == 1;
        String escaped = literal ? fixture.name : fixture.name.replace("_", "\\_");
        fixture.execute("REVOKE SELECT ON " + fixture.name + ".* FROM " + account.user);
        fixture.execute("GRANT SELECT, TRIGGER ON `" + escaped + "`.* TO " + account.user);
        var console = fixture.console("ds", account.source);
        assertEquals(
            1,
            console
                .executeUpdate(new SqlConsoleRequest("ds", "INSERT INTO ds.ordinary VALUES (2,7)"))
                .affectedRows());
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
        fixture.assertUnlocked();
      }
    }
  }

  @Test
  void matchingDatabasePatternsDoNotUnionPrivileges() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        boolean literal = fixture.scalar("SELECT @@partial_revokes") == 1;
        fixture.execute("GRANT TRIGGER ON " + fixture.name + ".* TO " + account.user);
        String selected = literal ? fixture.name : fixture.name.replace("_", "\\_");
        fixture.execute("GRANT SELECT ON `" + selected + "`.* TO " + account.user);
        try (var connection = fixture.pool.getConnection()) {
          FederationNativeTableEffectsDatabaseTest.trigger(connection, false, fixture.name);
        }
        try (var connection = account.source.getConnection();
            var statement = connection.createStatement();
            var rows =
                statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.TRIGGERS"
                        + " WHERE EVENT_OBJECT_SCHEMA='"
                        + fixture.name
                        + "' AND EVENT_OBJECT_TABLE='ordinary'")) {
          org.junit.jupiter.api.Assertions.assertTrue(rows.next());
          assertEquals(literal ? 1 : 0, rows.getInt(1));
        }
        rejected(
            fixture.console("ds", account.source),
            literal ? Code.SQL_WRITE_UNSUPPORTED : Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
        fixture.assertUnlocked();
      }
    }
  }

  @Test
  void globalGrantAndDatabaseRevocationRetainExplicitTablePermissions() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        var console = fixture.console("ds", account.source);
        fixture.execute("GRANT TRIGGER ON *.* TO " + account.user);
        assertEquals(
            1,
            console
                .executeUpdate(new SqlConsoleRequest("ds", "INSERT INTO ds.ordinary VALUES (2,7)"))
                .affectedRows());
        boolean partial = fixture.scalar("SELECT @@partial_revokes") == 1;
        fixture.execute(
            "REVOKE TRIGGER ON "
                + (partial ? fixture.name + ".*" : "*.*")
                + " FROM "
                + account.user);
        rejected(console, Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
        fixture.execute("GRANT TRIGGER ON " + fixture.name + ".ordinary TO " + account.user);
        assertEquals(
            1,
            console
                .executeUpdate(new SqlConsoleRequest("ds", "INSERT INTO ds.ordinary VALUES (3,7)"))
                .affectedRows());
        assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
        fixture.assertUnlocked();
      }
    }
  }

  @Test
  void activeRoleIncludesItsInheritedTableGrant() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        String inherited = "`n_" + account.name + "`@`%`";
        fixture.execute("CREATE ROLE " + inherited);
        try {
          fixture.execute("GRANT TRIGGER ON " + fixture.name + ".ordinary TO " + inherited);
          fixture.execute("GRANT " + inherited + " TO " + account.role);
          fixture.execute("GRANT " + account.role + " TO " + account.user);
          fixture.execute("SET DEFAULT ROLE " + account.role + " TO " + account.user);
          var console = fixture.console("ds", account.source);
          assertEquals(
              1,
              console
                  .executeUpdate(
                      new SqlConsoleRequest("ds", "INSERT INTO ds.ordinary VALUES (2,7)"))
                  .affectedRows());
          fixture.execute("REVOKE TRIGGER ON " + fixture.name + ".ordinary FROM " + inherited);
          rejected(console, Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
          fixture.assertUnlocked();
        } finally {
          fixture.execute("DROP ROLE " + inherited);
        }
      }
    }
  }

  @Test
  void directoryMetadataCannotBeAcceptedWhenItsTriggersAreHidden() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        fixture.execute(
            "REVOKE TRIGGER ON "
                + fixture.name
                + ".simpoint_physical_object_owners_v1 FROM "
                + account.user);
        try (var connection = account.source.getConnection()) {
          var failure =
              assertThrows(
                  PhysicalOwnershipException.class,
                  () -> PhysicalOwnershipSession.open(connection, fixture.name));
          assertEquals(PhysicalOwnershipException.Code.METADATA_UNAVAILABLE, failure.code());
        }
        fixture.execute(
            "CREATE TRIGGER "
                + fixture.name
                + ".directory_effect AFTER INSERT ON "
                + fixture.name
                + ".simpoint_physical_object_owners_v1 FOR EACH ROW INSERT INTO "
                + fixture.name
                + ".managed VALUES (99,99)");
        try {
          try (var connection = account.source.getConnection()) {
            var failure =
                assertThrows(
                    PhysicalOwnershipException.class,
                    () -> PhysicalOwnershipSession.open(connection, fixture.name));
            assertEquals(PhysicalOwnershipException.Code.METADATA_UNAVAILABLE, failure.code());
          }
          fixture.execute(
              "GRANT TRIGGER ON "
                  + fixture.name
                  + ".simpoint_physical_object_owners_v1 TO "
                  + account.user);
          try (var connection = account.source.getConnection()) {
            var failure =
                assertThrows(
                    PhysicalOwnershipException.class,
                    () -> PhysicalOwnershipSession.open(connection, fixture.name));
            assertEquals(PhysicalOwnershipException.Code.DIRECTORY_INVALID, failure.code());
          }
          assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
        } finally {
          fixture.execute("DROP TRIGGER " + fixture.name + ".directory_effect");
        }
        fixture.assertUnlocked();
      }
    }
  }

  private static void rejected(FederationSqlConsoleServiceImpl console, Code code) {
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () ->
                console.executeUpdate(
                    new SqlConsoleRequest("ds", "INSERT INTO ds.ordinary VALUES (1,7)")));
    assertEquals(code, failure.errorCode());
  }
}
