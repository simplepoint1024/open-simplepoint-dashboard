package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

/** Native metadata and public console execution, including retained names and namespace rules. */
class FederationNativeWriteOwnershipDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void managedTablesIndexesAndReferenceTargetsRejectNativeMutation(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("managed", true);
      fixture.create("ordinary", false);
      fixture.execute("INSERT INTO managed VALUES (1, 7)");
      fixture.execute("CREATE INDEX managed_ix ON managed (amount)");
      for (var sql :
          List.of(
              "ALTER TABLE ds.managed ADD COLUMN description VARCHAR(20)",
              "TRUNCATE TABLE ds.managed",
              "ALTER TABLE ds.managed RENAME TO relocated",
              "DROP TABLE ds.managed",
              "DROP TABLE ds.ordinary, ds.managed",
              "TRUNCATE TABLE ds.ordinary, ds.managed",
              "CREATE INDEX ds.another_ix ON ds.managed (id)",
              "DROP INDEX ds.managed_ix" + (postgres ? "" : " ON ds.managed"),
              "CREATE TABLE ds.child (id INTEGER, parent_id INTEGER REFERENCES ds.managed(id))",
              "ALTER TABLE ds.ordinary ADD CONSTRAINT managed_fk FOREIGN KEY (amount) REFERENCES"
                  + " ds.managed(id)",
              "TRUNCATE TABLE ds.simpoint_physical_object_owners_v1")) {
        rejected(fixture, sql, Code.SQL_WRITE_PROTECTED);
        fixture.assertUnlocked();
      }
      assertEquals(7, fixture.scalar("SELECT amount FROM managed WHERE id = 1"));
      assertEquals(0, exists(fixture, "child"));
      assertEquals(0, exists(fixture, "relocated"));
      // A query dependency is different from adding a foreign key to a managed target.
      ddl(fixture, "CREATE TABLE ds.copy AS SELECT * FROM ds.managed");
      assertEquals(7, fixture.scalar("SELECT amount FROM copy WHERE id = 1"));
    }
  }

  @Test
  void mysqlMultiRenameChecksEveryDestinationBeforeTheFirstMutation() throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(false, true)) {
      fixture.create("first_table", false);
      fixture.create("second_table", false);
      claim(fixture, "reserved_future");
      rejected(
          fixture,
          "RENAME TABLE ds.first_table TO ds.temporary_name, ds.second_table TO ds.reserved_future",
          Code.SQL_WRITE_PROTECTED);
      assertEquals(1, exists(fixture, "first_table"));
      assertEquals(1, exists(fixture, "second_table"));
      assertEquals(0, exists(fixture, "temporary_name"));
      ddl(
          fixture,
          "RENAME TABLE ds.first_table TO ds.temporary_name, ds.temporary_name TO ds.final_name");
      assertEquals(1, exists(fixture, "final_name"));
      fixture.assertUnlocked();
    }
  }

  @Test
  void postgresSearchPathCandidatesRemainLockedAgainstNewShadows() throws Exception {
    try (var current = new FederationSqlOwnershipFixture(true, true);
        var fallback = new FederationSqlOwnershipFixture(true, true);
        var rowOwner = fallback.pool.getConnection();
        var observer = current.pool.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      fallback.create("ordinary", false);
      fallback.execute("INSERT INTO ordinary VALUES (1,7)");
      current.sessionSql = "SET search_path TO " + current.name + ", " + fallback.name;
      rowOwner.setAutoCommit(false);
      try (var statement = rowOwner.createStatement();
          var rows = statement.executeQuery("SELECT id FROM ordinary WHERE id=1 FOR UPDATE")) {
        assertTrue(rows.next());
      }
      var write =
          executor.submit(
              () -> ddl(current, "ALTER TABLE ds.ordinary ADD COLUMN description VARCHAR(20)"));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!(current.lockPresent(observer) && fallback.lockPresent(observer))
            && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertTrue(current.lockPresent(observer));
        assertTrue(fallback.lockPresent(observer));
        for (var namespace : List.of(current.name, fallback.name)) {
          var refusal =
              assertThrows(
                  PhysicalOwnershipException.class,
                  () -> PhysicalOwnershipSession.open(observer, namespace));
          assertEquals(PhysicalOwnershipException.Code.SESSION_BUSY, refusal.code());
        }
      } finally {
        rowOwner.rollback();
      }
      write.get(20, TimeUnit.SECONDS);
      assertEquals(0, exists(current, "ordinary"));
      assertEquals(1, exists(fallback, "ordinary"));
      current.assertUnlocked();
      fallback.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void absentClaimedNamesRejectCreationAndRename(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      claim(fixture, "reserved_future");
      rejected(fixture, "CREATE TABLE ds.reserved_future (id INTEGER)", Code.SQL_WRITE_PROTECTED);
      rejected(
          fixture, "ALTER TABLE ds.ordinary RENAME TO reserved_future", Code.SQL_WRITE_PROTECTED);
      assertEquals(0, exists(fixture, "reserved_future"));
      assertEquals(1, exists(fixture, "ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unqualifiedRenameUsesTheNativeDestinationNamespace(boolean postgres) throws Exception {
    try (var current = new FederationSqlOwnershipFixture(postgres, true);
        var source = new FederationSqlOwnershipFixture(postgres, true)) {
      source.create("ordinary", false);
      var destination = postgres ? source : current;
      var other = postgres ? current : source;
      claim(destination, "reserved_dest");
      other.create("reserved_dest", false);
      rejected(
          current,
          "ALTER TABLE ds." + source.name + ".ordinary RENAME TO reserved_dest",
          Code.SQL_WRITE_PROTECTED);
      assertEquals(1, exists(source, "ordinary"));
      assertEquals(0, exists(destination, "reserved_dest"));
      ddl(current, "ALTER TABLE ds." + source.name + ".ordinary RENAME TO allowed_dest");
      assertEquals(1, exists(destination, "allowed_dest"));
      assertEquals(0, exists(other, "allowed_dest"));
      current.assertUnlocked();
      source.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unqualifiedForeignKeyResolvesRelativeToTheNativeContext(boolean postgres) throws Exception {
    try (var current = new FederationSqlOwnershipFixture(postgres, true);
        var child = new FederationSqlOwnershipFixture(postgres, true)) {
      current.create("parent", postgres);
      child.create("parent", !postgres);
      rejected(
          current,
          "CREATE TABLE ds."
              + child.name
              + ".child (id INTEGER, pid INTEGER, FOREIGN KEY (pid) REFERENCES parent(id))",
          Code.SQL_WRITE_PROTECTED);
      assertEquals(0, exists(child, "child"));
      current.assertUnlocked();
      child.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeUnicodeNamesAreNotReplacedByCalciteCaseFolding(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("straße", true);
      fixture.create("strasse", false);
      rejected(fixture, "TRUNCATE TABLE ds.straße", Code.SQL_WRITE_PROTECTED);
      ddl(fixture, "ALTER TABLE ds.strasse ADD COLUMN description VARCHAR(20)");
      assertEquals(1, exists(fixture, "straße"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void sessionTemporaryShadowsAndUnprovedCascadeAreRejected(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      rejected(fixture, "CREATE TEMPORARY TABLE ds.temp (id INTEGER)", Code.SQL_WRITE_UNSUPPORTED);
      rejected(fixture, "DROP TABLE ds.ordinary CASCADE", Code.SQL_WRITE_UNSUPPORTED);
      fixture.sessionSql = "CREATE TEMPORARY TABLE ordinary (id INTEGER)";
      try {
        rejected(
            fixture,
            "TRUNCATE TABLE ds.ordinary",
            postgres ? Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE : Code.SQL_WRITE_UNSUPPORTED);
      } finally {
        fixture.sessionSql = null;
      }
      assertEquals(7, fixture.scalar("SELECT amount FROM ordinary WHERE id=1"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingDirectoryAndNativeFailureDoNotLeaveLocksOrReportSuccess(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, false)) {
      rejected(
          fixture, "CREATE TABLE ds.ordinary (id INTEGER)", Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
      assertEquals(0, exists(fixture, "ordinary"));
    }
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      assertThrows(
          IllegalStateException.class,
          () -> ddl(fixture, "ALTER TABLE ds.ordinary ADD COLUMN id INTEGER"));
      fixture.assertUnlocked();
      ddl(fixture, "ALTER TABLE ds.ordinary ADD COLUMN description VARCHAR(20)");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeMergeChecksOwnershipBeforeHandingThePlanToTheDriver(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("managed", true);
      fixture.create("incoming", false);
      fixture.create("ordinary", false);
      fixture.execute("INSERT INTO incoming VALUES (1,7)");
      String tail =
          " AS t USING ds.incoming AS s ON t.id=s.id WHEN MATCHED THEN UPDATE SET amount=s.amount"
              + " WHEN NOT MATCHED THEN INSERT (id,amount) VALUES (s.id,s.amount)";
      var failure =
          assertThrows(
              FederationSqlWriteException.class,
              () -> fixture.write("MERGE INTO ds.managed" + tail, null));
      assertEquals(Code.SQL_WRITE_PROTECTED, failure.errorCode());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM managed"));
      if (postgres) {
        assertEquals(1, fixture.write("MERGE INTO ds.ordinary" + tail, null));
        fixture.execute("UPDATE incoming SET amount=9");
        assertEquals(1, fixture.write("MERGE INTO ds.ordinary" + tail, null));
        assertEquals(9, fixture.scalar("SELECT amount FROM ordinary WHERE id=1"));
      }
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeDdlRetainsNamespaceLockThroughoutItsDatabaseWait(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true);
        var rowOwner = fixture.pool.getConnection();
        var observer = fixture.pool.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      fixture.create("ordinary", false);
      fixture.execute("INSERT INTO ordinary VALUES (1,7)");
      rowOwner.setAutoCommit(false);
      try (var statement = rowOwner.createStatement();
          var rows = statement.executeQuery("SELECT id FROM ordinary WHERE id=1 FOR UPDATE")) {
        assertTrue(rows.next());
      }
      var write =
          executor.submit(
              () -> ddl(fixture, "ALTER TABLE ds.ordinary ADD COLUMN description VARCHAR(20)"));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fixture.lockPresent(observer) && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertTrue(fixture.lockPresent(observer));
        var refusal =
            assertThrows(
                PhysicalOwnershipException.class,
                () -> PhysicalOwnershipSession.open(observer, fixture.name));
        assertEquals(PhysicalOwnershipException.Code.SESSION_BUSY, refusal.code());
      } finally {
        rowOwner.rollback();
      }
      write.get(20, TimeUnit.SECONDS);
      fixture.assertUnlocked();
    }
  }

  private static long ddl(FederationSqlOwnershipFixture fixture, String sql) {
    return fixture.console.executeDdl(new SqlConsoleRequest("ds", sql)).affectedRows();
  }

  private static void rejected(FederationSqlOwnershipFixture fixture, String sql, Code code) {
    var failure = assertThrows(FederationSqlWriteException.class, () -> ddl(fixture, sql));
    assertEquals(code, failure.errorCode(), sql);
  }

  private static int exists(FederationSqlOwnershipFixture fixture, String table) throws Exception {
    return fixture.scalar(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
            + fixture.name
            + "' AND table_name='"
            + table
            + "'");
  }

  private static void claim(FederationSqlOwnershipFixture fixture, String table) throws Exception {
    try (var connection = fixture.pool.getConnection();
        var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
      session.claimNew(
          table, true, new PhysicalObjectOwner("dna-runtime", "retained-tenant", table));
    }
  }
}
