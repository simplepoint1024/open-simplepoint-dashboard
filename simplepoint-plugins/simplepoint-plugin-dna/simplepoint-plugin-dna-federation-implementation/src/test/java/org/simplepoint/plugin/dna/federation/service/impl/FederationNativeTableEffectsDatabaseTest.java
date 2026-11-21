package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

class FederationNativeTableEffectsDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void customTriggerCannotWriteToManagedTableThroughAnOrdinaryTarget(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.create("destination", false);
      try (var connection = fixture.pool.getConnection()) {
        trigger(connection, postgres, fixture.name);
      }
      rejected(fixture, "INSERT INTO ds.ordinary VALUES (1,7)");
      if (postgres) {
        rejected(
            fixture,
            "MERGE INTO ds.ordinary AS t USING (VALUES (1,7)) AS s(id,amount)"
                + " ON t.id=s.id WHEN NOT MATCHED THEN INSERT (id,amount) VALUES (s.id,s.amount)");
      }
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      // Reading a plain table does not execute its DML triggers.
      assertEquals(0, fixture.write("INSERT INTO ds.destination SELECT * FROM ds.ordinary", null));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void viewsCannotHideThePhysicalWriteTargetOrWriteQueryDependency(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("INSERT INTO managed VALUES (1,7)");
      fixture.execute("CREATE VIEW indirect AS SELECT id,amount FROM managed");
      rejected(fixture, "UPDATE ds.indirect SET amount=9 WHERE id=1");
      rejected(fixture, "INSERT INTO ds.ordinary SELECT * FROM ds.indirect");
      assertEquals(7, fixture.scalar("SELECT amount FROM managed"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void triggerAddedAfterPlanAdmissionIsDetectedBeforeExecution(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      var changes = new AtomicInteger();
      try (var hook =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
              (Object ignored) -> {
                try {
                  trigger(fixture.lastBorrowed, postgres, fixture.name);
                  changes.incrementAndGet();
                } catch (SQLException failure) {
                  throw new IllegalStateException("Trigger fault injection failed", failure);
                }
              })) {
        rejected(fixture, "UPDATE ds.ordinary SET amount=7 WHERE id=1");
      }
      assertEquals(1, changes.get());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void postgresInheritanceCannotMutateAnOwnedChildThroughItsOrdinaryParent() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("ALTER TABLE managed INHERIT ordinary");
      fixture.execute("INSERT INTO managed VALUES (1,7)");
      rejected(fixture, "UPDATE ds.ordinary SET amount=9 WHERE id=1");
      assertEquals(7, fixture.scalar("SELECT amount FROM managed"));
      fixture.assertUnlocked();
    }
  }

  static void trigger(Connection connection, boolean postgres, String namespace)
      throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      if (postgres) {
        statement.execute(
            "CREATE FUNCTION "
                + namespace
                + ".copy_record() RETURNS trigger"
                + " LANGUAGE plpgsql AS $$ BEGIN INSERT INTO "
                + namespace
                + ".managed VALUES (NEW.id,NEW.amount); RETURN NEW; END $$");
        statement.execute(
            "CREATE TRIGGER copy_record AFTER INSERT ON "
                + namespace
                + ".ordinary FOR EACH ROW EXECUTE FUNCTION "
                + namespace
                + ".copy_record()");
      } else {
        statement.execute(
            "CREATE TRIGGER "
                + namespace
                + ".copy_record AFTER INSERT ON "
                + namespace
                + ".ordinary FOR EACH ROW INSERT INTO "
                + namespace
                + ".managed VALUES (NEW.id,NEW.amount)");
      }
    }
  }

  private static void rejected(FederationSqlOwnershipFixture fixture, String sql) {
    var failure = assertThrows(FederationSqlWriteException.class, () -> fixture.write(sql, null));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }
}
