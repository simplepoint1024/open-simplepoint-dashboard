package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

/** Native storage routing must be checked independently of BASE TABLE / ordinary-table kind. */
class FederationNativeStorageEngineDatabaseTest {
  @ParameterizedTest
  @ValueSource(strings = {"MyISAM", "MEMORY"})
  void mysqlNontransactionalEngineCannotBeWriteTargetOrDmlSource(String engine) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      fixture.create("destination", false);
      fixture.execute("ALTER TABLE ordinary ENGINE=" + engine);
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      rejected(fixture, "INSERT INTO ds.ordinary VALUES (2,8)");
      rejected(fixture, "UPDATE ds.ordinary SET amount=9 WHERE id=1");
      rejected(fixture, "DELETE FROM ds.ordinary WHERE id=1");
      rejected(fixture, "INSERT INTO ds.destination SELECT * FROM ds.ordinary");
      assertEquals(7, fixture.scalar("SELECT amount FROM ordinary WHERE id=1"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM destination"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void mysqlMergeEngineCannotRouteAnOrdinaryNameToManagedTable() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("managed", true);
      fixture.create("destination", false);
      // Emulate a preexisting externally installed route. Runtime does not create this engine.
      fixture.execute("ALTER TABLE managed ENGINE=MyISAM");
      fixture.execute(
          "CREATE TABLE ordinary (id INTEGER PRIMARY KEY, amount INTEGER)"
              + " ENGINE=MRG_MyISAM UNION=(managed) INSERT_METHOD=LAST");
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      assertEquals(7, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
      rejected(fixture, "INSERT INTO ds.ordinary VALUES (2,8)");
      rejected(fixture, "UPDATE ds.ordinary SET amount=9 WHERE id=1");
      rejected(fixture, "DELETE FROM ds.ordinary WHERE id=1");
      rejected(fixture, "INSERT INTO ds.destination SELECT * FROM ds.ordinary");
      assertEquals(7, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM managed"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM destination"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void mysqlEngineChangedAfterPlanAdmissionIsRejectedBeforeExecution() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("ordinary", false);
      var changes = new AtomicInteger();
      try (var hook =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
              (Object ignored) -> {
                try (var statement = fixture.lastBorrowed.createStatement()) {
                  statement.setQueryTimeout(10);
                  statement.execute("ALTER TABLE ordinary ENGINE=MyISAM");
                  changes.incrementAndGet();
                } catch (SQLException failure) {
                  throw new IllegalStateException("Storage engine fault injection failed", failure);
                }
              })) {
        rejected(fixture, "UPDATE ds.ordinary SET amount=7 WHERE id=1");
      }
      assertEquals(1, changes.get());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void postgresCustomTableAccessMethodRequiresItsOwnVerifiedPolicy() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      String method = fixture.name;
      fixture.execute(
          "CREATE ACCESS METHOD " + method + " TYPE TABLE HANDLER pg_catalog.heap_tableam_handler");
      try {
        fixture.execute(
            "CREATE TABLE ordinary (id INTEGER PRIMARY KEY, amount INTEGER) USING " + method);
        fixture.create("destination", false);
        fixture.execute("INSERT INTO ordinary VALUES (1,7)");
        rejected(fixture, "INSERT INTO ds.ordinary VALUES (2,8)");
        rejected(fixture, "UPDATE ds.ordinary SET amount=9 WHERE id=1");
        rejected(fixture, "DELETE FROM ds.ordinary WHERE id=1");
        rejected(fixture, "INSERT INTO ds.destination SELECT * FROM ds.ordinary");
        rejected(
            fixture,
            "MERGE INTO ds.ordinary AS t USING (VALUES (2,8)) AS s(id,amount)"
                + " ON t.id=s.id WHEN NOT MATCHED THEN INSERT (id,amount) VALUES (s.id,s.amount)");
        assertEquals(7, fixture.scalar("SELECT amount FROM ordinary WHERE id=1"));
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM destination"));
        fixture.assertUnlocked();
      } finally {
        fixture.execute("DROP TABLE IF EXISTS ordinary");
        fixture.execute("DROP ACCESS METHOD " + method);
      }
    }
  }

  @Test
  void postgresAccessMethodChangedAfterPlanAdmissionIsRejectedBeforeExecution() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      String method = fixture.name;
      fixture.execute(
          "CREATE ACCESS METHOD " + method + " TYPE TABLE HANDLER pg_catalog.heap_tableam_handler");
      try {
        fixture.create("ordinary", false);
        var changes = new AtomicInteger();
        try (var hook =
            Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
                (Object ignored) -> {
                  try (var statement = fixture.lastBorrowed.createStatement()) {
                    statement.setQueryTimeout(10);
                    statement.execute("ALTER TABLE ordinary SET ACCESS METHOD " + method);
                    changes.incrementAndGet();
                  } catch (SQLException failure) {
                    throw new IllegalStateException(
                        "Access method fault injection failed", failure);
                  }
                })) {
          rejected(fixture, "UPDATE ds.ordinary SET amount=7 WHERE id=1");
        }
        assertEquals(1, changes.get());
        assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
        fixture.assertUnlocked();
      } finally {
        fixture.execute("DROP TABLE IF EXISTS ordinary");
        fixture.execute("DROP ACCESS METHOD " + method);
      }
    }
  }

  private static void rejected(FederationSqlOwnershipFixture fixture, String sql) {
    var failure = assertThrows(FederationSqlWriteException.class, () -> fixture.write(sql, null));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }
}
