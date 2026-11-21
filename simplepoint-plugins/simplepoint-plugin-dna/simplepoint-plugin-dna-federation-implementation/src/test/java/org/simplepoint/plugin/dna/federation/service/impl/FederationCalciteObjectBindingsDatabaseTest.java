package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

/** Native identity checks through the real console, preparation hook and physical write session. */
class FederationCalciteObjectBindingsDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unicodeAndQuotedNamesKeepNativeIdentity(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("straße", false);
      fixture.create("strasse", false);
      String quoted = postgres ? "\"MiXeD\"" : "`MiXeD`";
      fixture.create(quoted, false);
      assertEquals(1, fixture.write("INSERT INTO ds.straße VALUES (1,7)", null));
      assertEquals(1, fixture.write("INSERT INTO ds.strasse VALUES (1,9)", null));
      assertEquals(1, fixture.write("INSERT INTO ds.\"MiXeD\" VALUES (1,11)", null));
      var failure =
          assertThrows(
              FederationSqlWriteException.class,
              () -> fixture.write("INSERT INTO ds.\"mixed\" VALUES (2,13)", null));
      assertEquals(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE, failure.errorCode());
      assertEquals(7, fixture.scalar("SELECT amount FROM straße"));
      assertEquals(9, fixture.scalar("SELECT amount FROM strasse"));
      assertEquals(11, fixture.scalar("SELECT SUM(amount) FROM " + quoted));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void selectedDefaultNamespaceAndExplicitReadNamespaceRemainIndependent(boolean postgres)
      throws Exception {
    try (var current = new FederationSqlOwnershipFixture(postgres, true);
        var other = new FederationSqlOwnershipFixture(postgres, true)) {
      current.create("ordinary", false);
      other.create("ordinary", false);
      current.execute("INSERT INTO ordinary VALUES (1,7)");
      var request =
          new SqlConsoleRequest(
              "ds",
              "INSERT INTO ordinary SELECT id,amount FROM ds." + current.name + ".ordinary",
              "ds." + other.name);
      assertEquals(1, current.console.executeUpdate(request).affectedRows());
      assertEquals(7, other.scalar("SELECT amount FROM ordinary"));
      assertEquals(1, current.scalar("SELECT COUNT(*) FROM ordinary"));
      current.assertUnlocked();
      other.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void readBindingsCannotSilentlySelectAnotherCaseSensitiveTable(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      String quoted = postgres ? "\"SOURCE\"" : "`SOURCE`";
      fixture.create(quoted, false);
      fixture.execute("INSERT INTO " + quoted + " VALUES (1,7)");
      var failure =
          assertThrows(
              FederationSqlWriteException.class,
              () -> fixture.write("INSERT INTO ds.ordinary SELECT * FROM ds.\"source\"", null));
      assertEquals(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE, failure.errorCode());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      assertEquals(1, fixture.write("INSERT INTO ds.ordinary SELECT * FROM ds.\"SOURCE\"", null));
      assertEquals(7, fixture.scalar("SELECT amount FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void changedNativeObjectAfterApprovalRejectsBeforeWriting(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      var disruptions = new AtomicInteger();
      try (var hook =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
              (Object ignored) -> {
                try (var statement = fixture.lastBorrowed.createStatement()) {
                  if (postgres) {
                    statement.execute("DROP TABLE ordinary");
                    statement.execute(
                        "CREATE TABLE ordinary (id INTEGER PRIMARY KEY, amount INTEGER)");
                  } else {
                    statement.execute(
                        "CREATE TEMPORARY TABLE ordinary (id INTEGER PRIMARY KEY, amount INTEGER)");
                  }
                  disruptions.incrementAndGet();
                } catch (SQLException failure) {
                  throw new IllegalStateException(
                      "Native object change fault injection failed", failure);
                }
              })) {
        var failure =
            assertThrows(
                FederationSqlWriteException.class,
                () -> fixture.write("UPDATE ds.ordinary SET amount=7 WHERE id=1", null));
        assertEquals(
            postgres ? Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE : Code.SQL_WRITE_UNSUPPORTED,
            failure.errorCode());
      }
      assertEquals(1, disruptions.get());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }
}
