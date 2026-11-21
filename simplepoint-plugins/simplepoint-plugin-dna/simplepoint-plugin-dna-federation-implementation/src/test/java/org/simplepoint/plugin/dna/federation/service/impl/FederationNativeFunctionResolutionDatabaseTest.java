package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;

class FederationNativeFunctionResolutionDatabaseTest {
  @Test
  void postgresMoreExactOverloadCanWinEvenWithPgCatalogImplicitlyFirst() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("managed", true);
      fixture.execute("CREATE TABLE ordinary(id integer PRIMARY KEY,name varchar(80))");
      fixture.execute("INSERT INTO ordinary VALUES(1,'NAME')");
      fixture.execute(
          "CREATE FUNCTION lower(varchar) RETURNS text LANGUAGE plpgsql AS $$"
              + " BEGIN INSERT INTO managed VALUES (1,7); RETURN $1; END $$");
      fixture.execute("UPDATE ordinary SET name=lower(name)");
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.execute("TRUNCATE managed");
      rejected(fixture, "UPDATE ds.ordinary SET name=LOWER(name)");
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void postgresCustomOperatorsCannotTurnOrdinaryArithmeticIntoAnotherWrite() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("managed", true);
      fixture.create("ordinary", false);
      fixture.execute("INSERT INTO ordinary VALUES(1,7)");
      fixture.execute(
          "CREATE FUNCTION hidden_add(integer,integer) RETURNS integer"
              + " LANGUAGE plpgsql AS $$ BEGIN INSERT INTO managed VALUES(1,7); RETURN 99; END $$");
      fixture.execute("CREATE OPERATOR + (LEFTARG=integer,RIGHTARG=integer,FUNCTION=hidden_add)");
      fixture.sessionSql = "SET search_path TO " + fixture.name + ",pg_catalog";
      fixture.execute("UPDATE ordinary SET amount=amount+1");
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.execute("TRUNCATE managed");
      rejected(fixture, "UPDATE ds.ordinary SET amount=amount+1");
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void postgresCustomCastBetweenBuiltinTypesIsNotTrusted() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(true, true)) {
      fixture.create("managed", true);
      fixture.execute("CREATE TABLE ordinary(id integer PRIMARY KEY, payload jsonb, day date)");
      fixture.execute("INSERT INTO ordinary(id,payload) VALUES(1,'{}')");
      fixture.execute(
          "CREATE FUNCTION hidden_cast(jsonb) RETURNS date LANGUAGE plpgsql AS $$"
              + " BEGIN INSERT INTO managed VALUES(1,7); RETURN DATE '2026-10-28'; END $$");
      fixture.execute("CREATE CAST(jsonb AS date) WITH FUNCTION hidden_cast(jsonb)");
      fixture.execute("UPDATE ordinary SET day=CAST(payload AS date)");
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.execute("TRUNCATE managed");
      rejected(fixture, "UPDATE ds.ordinary SET day=CAST(payload AS DATE)");
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void mysqlWhitespaceCannotSelectStoredRoutineInPlaceOfBuiltin() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("managed", true);
      fixture.create("ordinary", false);
      fixture.execute(
          "CREATE FUNCTION `count`(n integer) RETURNS integer DETERMINISTIC"
              + " MODIFIES SQL DATA BEGIN INSERT INTO managed VALUES(n,7); RETURN 7; END");
      fixture.execute("INSERT INTO ordinary(id,amount) VALUES(1,count (1))");
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM managed"));
      fixture.execute("TRUNCATE managed");
      rejected(fixture, "INSERT INTO ds.ordinary(id,amount) VALUES(2,COUNT (2))");
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  private static void rejected(FederationSqlOwnershipFixture fixture, String sql) {
    var failure = assertThrows(FederationSqlWriteException.class, () -> fixture.write(sql, null));
    assertEquals(Code.SQL_WRITE_UNSUPPORTED, failure.errorCode());
  }
}
