package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

class FederationNativeExpressionEffectsDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void explicitDefaultNullAndParametersKeepTheirDistinctNativeMeaning(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.execute(
          "CREATE TABLE ordinary(id integer PRIMARY KEY, amount integer DEFAULT 7,"
              + " name varchar(80) DEFAULT '默认')");
      assertEquals(1, fixture.write("INSERT INTO ds.ordinary VALUES(1,DEFAULT,DEFAULT)", null));
      assertEquals(
          1,
          fixture.write(
              "INSERT INTO ds.ordinary(id,amount,name) VALUES(?,?,?)",
              java.util.Arrays.asList(2, null, "日本語; DEFAULT")));
      assertEquals(7, fixture.scalar("SELECT amount FROM ordinary WHERE id=1"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM ordinary WHERE id=2 AND amount IS NULL"
                  + " AND name='日本語; DEFAULT'"));
      if (postgres) {
        assertEquals(1, fixture.write("INSERT INTO ds.ordinary VALUES(3)", null));
        assertEquals(7, fixture.scalar("SELECT amount FROM ordinary WHERE id=3"));
      }
      fixture.assertUnlocked();
    }
  }

  @Test
  void nativeInsertRechecksDefaultsUnderTheRetainedNamespaceLock() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute(
          "CREATE FUNCTION hidden_default() RETURNS integer LANGUAGE plpgsql AS $$"
              + " BEGIN INSERT INTO managed VALUES (1,7); RETURN 7; END $$");
      try (var connection = fixture.pool.getConnection();
          var scope =
              new FederationSqlWriteOwnership()
                  .openNative(
                      connection,
                      FederationDmlPlan.parse("INSERT INTO ds.ordinary(id) VALUES(1)"),
                      "ds")) {
        try (var statement = connection.createStatement()) {
          statement.execute(
              "ALTER TABLE ordinary ALTER COLUMN amount SET DEFAULT hidden_default()");
        }
        var failure = assertThrows(FederationSqlWriteException.class, scope::beforeExecution);
        assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
      }
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"default", "check"})
  void postgresImplicitFunctionCannotWriteAnOwnedTable(String effect) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("managed", true);
      fixture.execute(
          "CREATE FUNCTION write_hidden() RETURNS integer LANGUAGE plpgsql AS $$"
              + " BEGIN INSERT INTO managed VALUES (1,7); RETURN 7; END $$");
      fixture.execute(
          "CREATE TABLE ordinary (id integer PRIMARY KEY, amount integer "
              + (effect.equals("default") ? "DEFAULT write_hidden()" : "CHECK (write_hidden()>0)")
              + ")");
      // Establish the real implicit effect outside the console, then return to a clean state.
      fixture.execute("INSERT INTO ordinary(id) VALUES (1)");
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.execute("TRUNCATE ordinary,managed");
      rejected(fixture, "INSERT INTO ds.ordinary(id) VALUES (2)");
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"index", "generated"})
  void immutableLabelDoesNotMakeAnUnknownNativeFunctionSafe(String effect) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.execute("CREATE SEQUENCE unrelated_sequence");
      fixture.execute(
          "CREATE FUNCTION hidden_effect(integer) RETURNS integer IMMUTABLE LANGUAGE plpgsql AS $$"
              + " BEGIN PERFORM nextval('unrelated_sequence'); RETURN $1; END $$");
      fixture.create("ordinary", false);
      if (effect.equals("index")) {
        fixture.execute("CREATE INDEX hidden_index ON ordinary(hidden_effect(amount))");
      } else {
        fixture.execute(
            "ALTER TABLE ordinary ADD COLUMN calculated integer"
                + " GENERATED ALWAYS AS (hidden_effect(amount)) STORED");
      }
      fixture.execute("INSERT INTO ordinary(id,amount) VALUES(1,7)");
      int before = fixture.scalar("SELECT last_value FROM unrelated_sequence");
      rejected(fixture, "INSERT INTO ds.ordinary(id,amount) VALUES(2,8)");
      assertEquals(before, fixture.scalar("SELECT last_value FROM unrelated_sequence"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void ownedSerialDefaultsRemainUsableButCannotAdvanceAnUnrelatedSequence() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.execute("CREATE TABLE ordinary(id serial PRIMARY KEY, amount integer DEFAULT 7)");
      assertEquals(1, fixture.write("INSERT INTO ds.ordinary(amount) VALUES(8)", null));
      assertEquals(1, fixture.scalar("SELECT id FROM ordinary"));
      fixture.execute("CREATE SEQUENCE unrelated_sequence");
      fixture.execute(
          "ALTER TABLE ordinary ALTER COLUMN id SET DEFAULT nextval('unrelated_sequence')");
      rejected(fixture, "INSERT INTO ds.ordinary(amount) VALUES(9)");
      assertEquals(
          0,
          fixture.scalar("SELECT CASE WHEN is_called THEN 1 ELSE 0 END FROM unrelated_sequence"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void postgresCustomColumnDomainIsNotTreatedAsItsBuiltinBaseType() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("managed", true);
      fixture.execute(
          "CREATE FUNCTION hidden_check(integer) RETURNS boolean LANGUAGE plpgsql AS $$"
              + " BEGIN INSERT INTO managed VALUES ($1,7); RETURN true; END $$");
      fixture.execute("CREATE DOMAIN hidden_domain AS integer CHECK(hidden_check(VALUE))");
      fixture.execute("CREATE TABLE ordinary(id integer PRIMARY KEY, amount hidden_domain)");
      rejected(fixture, "INSERT INTO ds.ordinary(id,amount) VALUES(1,7)");
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void commonNativeDefaultsChecksAndIndexesPreserveOrdinaryWrites(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.execute(
          "CREATE TABLE ordinary(id integer PRIMARY KEY, amount integer DEFAULT 7, name varchar(80)"
              + " DEFAULT '中文 日本語', created_at timestamp DEFAULT CURRENT_TIMESTAMP,"
              + " CHECK(amount>=0))");
      fixture.execute("CREATE INDEX ordinary_amount ON ordinary(amount)");
      fixture.execute(
          postgres
              ? "CREATE INDEX ordinary_lower ON ordinary(lower(name))"
              : "CREATE INDEX ordinary_lower ON ordinary((lower(name)))");
      assertEquals(1, fixture.write("INSERT INTO ds.ordinary(id) VALUES(1)", null));
      assertEquals(7, fixture.scalar("SELECT amount FROM ordinary"));
      assertEquals(1, fixture.write("UPDATE ds.ordinary SET amount=9 WHERE id=1", null));
      assertEquals(1, fixture.write("DELETE FROM ds.ordinary WHERE id=1", null));
      fixture.assertUnlocked();
    }
  }

  @Test
  void mysqlLiteralDefaultResemblingFunctionCallIsNotExecutedOrParsed() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.execute(
          "CREATE TABLE ordinary(id integer PRIMARY KEY, name varchar(80)"
              + " DEFAULT 'untrusted_function()', token binary(16) DEFAULT (uuid_to_bin(uuid())))");
      assertEquals(1, fixture.write("INSERT INTO ds.ordinary(id) VALUES(1)", null));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM ordinary WHERE name='untrusted_function()'"
                  + " AND LENGTH(token)=16"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void mysqlVirtualGeneratedColumnsAreCheckedEvenForReadDependencies() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.execute(
          "CREATE TABLE ordinary(id integer PRIMARY KEY, amount integer,"
              + " calculated integer GENERATED ALWAYS AS (crc32(amount)) VIRTUAL)");
      fixture.create("destination", false);
      rejected(fixture, "INSERT INTO ds.destination SELECT id,calculated FROM ds.ordinary");
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM destination"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void newDefaultAfterPlanningIsRecheckedOnTheExecutionConnection() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute(
          "CREATE FUNCTION hidden_default() RETURNS integer LANGUAGE plpgsql AS $$"
              + " BEGIN INSERT INTO managed VALUES (1,7); RETURN 7; END $$");
      try (var hook =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
              (Object ignored) -> {
                try (var statement = fixture.lastBorrowed.createStatement()) {
                  statement.execute(
                      "ALTER TABLE ordinary ALTER COLUMN amount SET DEFAULT hidden_default()");
                } catch (java.sql.SQLException failure) {
                  throw new IllegalStateException("Default fault injection failed", failure);
                }
              })) {
        rejected(fixture, "UPDATE ds.ordinary SET amount=7 WHERE id=1");
      }
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  private static void rejected(FederationSqlOwnershipFixture fixture, String sql) {
    var failure = assertThrows(FederationSqlWriteException.class, () -> fixture.write(sql, null));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }
}
