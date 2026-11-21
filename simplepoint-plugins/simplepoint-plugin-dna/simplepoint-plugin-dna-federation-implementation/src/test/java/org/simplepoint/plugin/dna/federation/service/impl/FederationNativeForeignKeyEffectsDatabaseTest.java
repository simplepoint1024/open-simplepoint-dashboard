package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

class FederationNativeForeignKeyEffectsDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void cascadingParentCannotModifyManagedChildThroughAnOrdinaryName(boolean postgres)
      throws Exception {
    for (String action : new String[] {"CASCADE", "SET NULL"}) {
      try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
        fixture.create("ordinary", false);
        fixture.create("managed", true);
        fixture.create("destination", false);
        foreignKey(fixture, action);
        fixture.execute("INSERT INTO ordinary VALUES (1,7)");
        fixture.execute("INSERT INTO managed VALUES (99,1)");
        rejected(fixture, "DELETE FROM ds.ordinary WHERE id=1");
        rejected(fixture, "UPDATE ds.ordinary SET id=2 WHERE id=1");
        if (postgres) {
          rejected(
              fixture,
              "MERGE INTO ds.ordinary AS t USING (VALUES (1,2)) AS s(old_id,new_id)"
                  + " ON t.id=s.old_id WHEN MATCHED THEN UPDATE SET id=s.new_id");
        }
        assertEquals(1, fixture.scalar("SELECT id FROM ordinary"));
        assertEquals(1, fixture.scalar("SELECT amount FROM managed WHERE id=99"));
        // Foreign keys do not perform cascading writes when the table is only read.
        assertEquals(
            1, fixture.write("INSERT INTO ds.destination SELECT * FROM ds.ordinary", null));
        fixture.assertUnlocked();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restrictiveForeignKeyDoesNotDisableUnrelatedOrdinaryWrites(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      fixture.execute("INSERT INTO managed VALUES (99,1)");
      for (String action : new String[] {"RESTRICT", "NO ACTION"}) {
        foreignKey(fixture, action);
        assertEquals(1, fixture.write("UPDATE ds.ordinary SET amount=8 WHERE id=1", null));
        assertEquals(1, fixture.write("INSERT INTO ds.ordinary VALUES (2,9)", null));
        assertEquals(1, fixture.write("DELETE FROM ds.ordinary WHERE id=2", null));
        assertEquals(1, fixture.scalar("SELECT amount FROM managed WHERE id=99"));
        assertEquals(8, fixture.scalar("SELECT amount FROM ordinary WHERE id=1"));
        fixture.execute(
            "ALTER TABLE managed DROP "
                + (postgres ? "CONSTRAINT" : "FOREIGN KEY")
                + " reference_parent");
      }
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void cascadeAddedAfterPlanAdmissionIsRejectedBeforeExecution(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      fixture.execute("INSERT INTO managed VALUES (99,1)");
      var changes = new AtomicInteger();
      try (var hook =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
              (Object ignored) -> {
                try (var statement = fixture.lastBorrowed.createStatement()) {
                  statement.setQueryTimeout(10);
                  statement.execute(
                      "ALTER TABLE managed ADD CONSTRAINT reference_parent FOREIGN KEY (amount)"
                          + " REFERENCES ordinary(id) ON DELETE CASCADE ON UPDATE CASCADE");
                  changes.incrementAndGet();
                } catch (SQLException failure) {
                  throw new IllegalStateException("Foreign key fault injection failed", failure);
                }
              })) {
        rejected(fixture, "DELETE FROM ds.ordinary WHERE id=1");
      }
      assertEquals(1, changes.get());
      assertEquals(1, fixture.scalar("SELECT id FROM ordinary"));
      assertEquals(1, fixture.scalar("SELECT amount FROM managed WHERE id=99"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void mysqlRequiresCompleteMetadataForHiddenCrossDatabaseCascades() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true);
        var child = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      child.create("managed", true);
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      child.execute(
          "ALTER TABLE managed ADD CONSTRAINT reference_parent FOREIGN KEY (amount) REFERENCES "
              + fixture.name
              + ".ordinary(id) ON DELETE CASCADE");
      child.execute("INSERT INTO managed VALUES (99,1)");
      try (var account = new FederationMysqlMetadataAccountFixture(fixture)) {
        fixture.execute("GRANT DELETE,TRIGGER ON " + fixture.name + ".ordinary TO " + account.user);
        var console = fixture.console("ds", account.source);
        var request = new SqlConsoleRequest("ds", "DELETE FROM ds.ordinary WHERE id=1");
        // General information_schema and JDBC exported keys hide this unrelated database.
        try (var connection = account.source.getConnection();
            var statement =
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS"
                        + " WHERE CONSTRAINT_SCHEMA=?")) {
          statement.setString(1, child.name);
          try (var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
          }
        }
        assertEquals(
            Code.SQL_WRITE_UNSUPPORTED,
            assertThrows(FederationSqlWriteException.class, () -> console.executeUpdate(request))
                .errorCode());
        fixture.execute("REVOKE PROCESS ON *.* FROM " + account.user);
        assertEquals(
            Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE,
            assertThrows(FederationSqlWriteException.class, () -> console.executeUpdate(request))
                .errorCode());
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
        assertEquals(1, child.scalar("SELECT amount FROM managed WHERE id=99"));
        child.execute("DROP TABLE managed");
        assertEquals(
            Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE,
            assertThrows(FederationSqlWriteException.class, () -> console.executeUpdate(request))
                .errorCode());
        fixture.execute("GRANT PROCESS ON *.* TO " + account.role);
        fixture.execute("GRANT " + account.role + " TO " + account.user);
        fixture.execute("SET DEFAULT ROLE " + account.role + " TO " + account.user);
        assertEquals(1, console.executeUpdate(request).affectedRows());
        fixture.assertUnlocked();
      }
    }
  }

  @Test
  void postgresSetDefaultCannotModifyManagedChild() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("ALTER TABLE managed ALTER COLUMN amount SET DEFAULT 0");
      foreignKey(fixture, "SET DEFAULT");
      fixture.execute("INSERT INTO ordinary VALUES (0,0),(1,7)");
      fixture.execute("INSERT INTO managed VALUES (99,1)");
      rejected(fixture, "DELETE FROM ds.ordinary WHERE id=1");
      rejected(fixture, "UPDATE ds.ordinary SET id=2 WHERE id=1");
      assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      assertEquals(1, fixture.scalar("SELECT amount FROM managed WHERE id=99"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void mysqlUnicodeReferenceNamesUseNativeLiteralIdentity() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.execute(
          "CREATE TABLE `父表@取引` (id INTEGER PRIMARY KEY, amount INTEGER) ENGINE=InnoDB");
      fixture.create("managed", true);
      fixture.execute(
          "ALTER TABLE managed ADD CONSTRAINT reference_parent FOREIGN KEY(amount)"
              + " REFERENCES `父表@取引`(id) ON DELETE CASCADE");
      fixture.execute("INSERT INTO `父表@取引` VALUES (1,7)");
      fixture.execute("INSERT INTO managed VALUES (99,1)");
      rejected(fixture, "DELETE FROM ds.\"父表@取引\" WHERE id=1");
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM `父表@取引`"));
      assertEquals(1, fixture.scalar("SELECT amount FROM managed WHERE id=99"));
      fixture.assertUnlocked();
    }
  }

  private static void foreignKey(FederationSqlOwnershipFixture fixture, String action)
      throws SQLException {
    fixture.execute(
        "ALTER TABLE managed ADD CONSTRAINT reference_parent FOREIGN KEY (amount)"
            + " REFERENCES ordinary(id) ON DELETE "
            + action
            + " ON UPDATE "
            + action);
  }

  private static void rejected(FederationSqlOwnershipFixture fixture, String sql) {
    assertEquals(
        Code.SQL_WRITE_UNSUPPORTED,
        assertThrows(FederationSqlWriteException.class, () -> fixture.write(sql, null))
            .errorCode());
  }
}
