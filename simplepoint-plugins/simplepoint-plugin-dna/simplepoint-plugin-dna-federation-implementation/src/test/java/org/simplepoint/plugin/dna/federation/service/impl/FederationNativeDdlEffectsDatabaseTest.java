package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

/**
 * Native witnesses demonstrate indirect writes, followed by rejection through actual console DDL.
 */
class FederationNativeDdlEffectsDatabaseTest {
  @Test
  void postgresEventTriggerCannotWriteManagedRowsThroughOrdinaryDdl() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("INSERT INTO managed VALUES (1,7)");
      String trigger = eventTrigger(fixture);
      try {
        fixture.execute("ALTER TABLE ordinary ADD COLUMN witness INTEGER");
        assertEquals(8, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
        fixture.execute("UPDATE managed SET amount=7 WHERE id=1");
        rejected(fixture, "ALTER TABLE ds.ordinary ADD COLUMN blocked INTEGER");
        assertEquals(7, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
        assertEquals(
            0,
            fixture.scalar(
                "SELECT COUNT(*) FROM information_schema.columns"
                    + " WHERE table_schema='"
                    + fixture.name
                    + "' AND table_name='ordinary'"
                    + " AND column_name='blocked'"));
        fixture.execute("ALTER EVENT TRIGGER " + trigger + " DISABLE");
        fixture.console.executeDdl(
            new SqlConsoleRequest("ds", "ALTER TABLE ds.ordinary ADD COLUMN allowed INTEGER"));
        assertEquals(7, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
        fixture.assertUnlocked();
      } finally {
        fixture.execute("DROP EVENT TRIGGER IF EXISTS " + trigger);
      }
    }
  }

  @Test
  void eventTriggerEnabledAfterPreparationIsRecheckedOnTheExecutingConnection() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("INSERT INTO managed VALUES (1,7)");
      try (var connection = fixture.pool.getConnection();
          var scope =
              new FederationSqlWriteOwnership()
                  .openNative(
                      connection,
                      FederationDdlPlan.parse("ALTER TABLE ds.ordinary ADD COLUMN blocked INTEGER"),
                      "ds")) {
        String trigger = eventTrigger(fixture);
        try {
          var failure = assertThrows(FederationSqlWriteException.class, scope::beforeExecution);
          assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
          assertEquals(7, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
        } finally {
          fixture.execute("DROP EVENT TRIGGER IF EXISTS " + trigger);
        }
      }
      fixture.assertUnlocked();
    }
  }

  @Test
  void postgresTruncateTriggerCannotMutateManagedRows() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      fixture.execute("INSERT INTO managed VALUES (1,7)");
      fixture.execute(
          "CREATE FUNCTION truncate_effect() RETURNS trigger LANGUAGE plpgsql AS $$"
              + " BEGIN UPDATE "
              + fixture.name
              + ".managed SET amount=amount+1; RETURN NULL; END $$");
      fixture.execute(
          "CREATE TRIGGER indirect_truncate BEFORE TRUNCATE ON ordinary"
              + " FOR EACH STATEMENT EXECUTE FUNCTION truncate_effect()");
      fixture.execute("TRUNCATE ordinary");
      assertEquals(8, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
      fixture.execute("UPDATE managed SET amount=7 WHERE id=1");
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      rejected(fixture, "TRUNCATE TABLE ds.ordinary");
      assertEquals(7, fixture.scalar("SELECT amount FROM managed WHERE id=1"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  private static String eventTrigger(FederationSqlOwnershipFixture fixture) throws Exception {
    String name = "ddl_effect_" + UUID.randomUUID().toString().replace("-", "");
    fixture.execute(
        "CREATE FUNCTION ddl_effect() RETURNS event_trigger LANGUAGE plpgsql AS $$"
            + " BEGIN IF current_schema()='"
            + fixture.name
            + "' THEN UPDATE "
            + fixture.name
            + ".managed SET amount=amount+1; END IF; END $$");
    fixture.execute(
        "CREATE EVENT TRIGGER "
            + name
            + " ON ddl_command_end WHEN TAG IN ('ALTER TABLE') EXECUTE FUNCTION ddl_effect()");
    return name;
  }

  private static void rejected(FederationSqlOwnershipFixture fixture, String sql) {
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () -> fixture.console.executeDdl(new SqlConsoleRequest("ds", sql)));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }
}
