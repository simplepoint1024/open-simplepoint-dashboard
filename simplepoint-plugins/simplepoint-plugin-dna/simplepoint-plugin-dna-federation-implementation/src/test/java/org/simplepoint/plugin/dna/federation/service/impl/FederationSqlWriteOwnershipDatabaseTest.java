package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.calcite.runtime.Hook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;

/** Real console, Calcite, namespace ledger and locks; only control-plane lookups are fixtures. */
class FederationSqlWriteOwnershipDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostLockAfterPlanApprovalRejectsBeforeNativeExecution(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      var disruptions = new AtomicInteger();
      try (var hook =
          Hook.PLAN_BEFORE_IMPLEMENTATION.addThread(
              (Object ignored) -> {
                try (var statement = fixture.lastBorrowed.createStatement()) {
                  statement.execute(
                      postgres
                          ? "SELECT pg_catalog.pg_advisory_unlock_all()"
                          : "SELECT RELEASE_ALL_LOCKS()");
                  disruptions.incrementAndGet();
                } catch (SQLException failure) {
                  throw new IllegalStateException(
                      "Test could not release the native lock", failure);
                }
              })) {
        var rejection =
            assertThrows(
                FederationSqlWriteException.class,
                () -> fixture.write("UPDATE ds.ordinary SET amount=? WHERE id=?", List.of(7, 1)));
        assertEquals(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE, rejection.errorCode());
      }
      assertEquals(1, disruptions.get());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void inheritedTransactionCannotBeCommittedByAdmission(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.dirtyBorrow = true;
      try {
        var rejection =
            assertThrows(
                FederationSqlWriteException.class,
                () -> fixture.write("INSERT INTO ds.ordinary VALUES (1, 7)", null));
        assertEquals(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE, rejection.errorCode());
      } finally {
        fixture.dirtyBorrow = false;
      }
      assertTrue(fixture.lastBorrowed.isClosed());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void ordinaryCrudAndReadFromManagedTableKeepTheirBehavior(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.create("managed", true);
      fixture.execute("INSERT INTO managed VALUES (1, 7)");
      assertEquals(1, fixture.write("INSERT INTO ds.ordinary VALUES (?, ?)", List.of(2, 3)));
      assertEquals(
          1, fixture.write("INSERT INTO ds.ordinary SELECT id, amount FROM ds.managed", null));
      assertEquals(
          1, fixture.write("UPDATE ds.ordinary SET amount = ? WHERE id = ?", List.of(9, 2)));
      assertEquals(16, fixture.scalar("SELECT SUM(amount) FROM ordinary"));
      assertEquals(1, fixture.write("DELETE FROM ds.ordinary WHERE id = ?", List.of(2)));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retainedManagedTargetsAndDirectoryRejectAllStandardDml(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("managed", true);
      fixture.execute("INSERT INTO managed VALUES (1, 7)");
      for (var sql :
          List.of(
              "INSERT INTO ds.managed VALUES (2, 9)",
              "UPDATE ds.managed SET amount = 9 WHERE id = 1",
              "DELETE FROM ds.managed WHERE id = 1",
              "DELETE FROM ds.simpoint_physical_object_owners_v1")) {
        var rejection =
            assertThrows(FederationSqlWriteException.class, () -> fixture.write(sql, null));
        assertEquals(Code.SQL_WRITE_PROTECTED, rejection.errorCode());
        fixture.assertUnlocked();
      }
      assertEquals(7, fixture.scalar("SELECT SUM(amount) FROM managed"));
      // Different logical datasource identities cannot hide the physical owner's retained claim.
      var alias = fixture.console("alias");
      var failure =
          assertThrows(
              FederationSqlWriteException.class,
              () ->
                  alias.executeUpdate(new SqlConsoleRequest("alias", "DELETE FROM alias.managed")));
      assertEquals(Code.SQL_WRITE_PROTECTED, failure.errorCode());
      fixture.assertUnlocked();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingDirectoryAndCompetingRegistrationRefuseBeforeMutation(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, false)) {
      fixture.create("ordinary", false);
      var failure =
          assertThrows(
              FederationSqlWriteException.class,
              () -> fixture.write("INSERT INTO ds.ordinary VALUES (1, 7)", null));
      assertEquals(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE, failure.errorCode());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
    }
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true);
        var competitor = fixture.pool.getConnection();
        var registration = PhysicalOwnershipSession.open(competitor, fixture.name)) {
      fixture.create("ordinary", false);
      var failure =
          assertThrows(
              FederationSqlWriteException.class,
              () -> fixture.write("INSERT INTO ds.ordinary VALUES (1, 7)", null));
      assertEquals(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE, failure.errorCode());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM ordinary"));
      registration.verifyExecutionSession();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void physicalFailureReleasesAdmissionAndFollowingOperationCanSucceed(boolean postgres)
      throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true)) {
      fixture.create("ordinary", false);
      fixture.execute("INSERT INTO ordinary VALUES (1, 7)");
      assertThrows(
          IllegalStateException.class,
          () -> fixture.write("INSERT INTO ds.ordinary VALUES (1, 99)", null));
      fixture.assertUnlocked();
      assertEquals(1, fixture.write("INSERT INTO ds.ordinary VALUES (2, 8)", null));
      assertEquals(15, fixture.scalar("SELECT SUM(amount) FROM ordinary"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void namespaceLockRemainsHeldWhileNativeDmlWaitsForItsRow(boolean postgres) throws Exception {
    try (var fixture = new FederationSqlOwnershipFixture(postgres, true);
        var rowOwner = fixture.pool.getConnection();
        var observer = fixture.pool.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      fixture.create("ordinary", false);
      fixture.execute("INSERT INTO ordinary VALUES (1, 7)");
      rowOwner.setAutoCommit(false);
      try (var statement = rowOwner.createStatement();
          var rows = statement.executeQuery("SELECT id FROM ordinary WHERE id = 1 FOR UPDATE")) {
        assertTrue(rows.next());
      }
      var write = executor.submit(() -> fixture.write("UPDATE ds.ordinary SET amount = 9", null));
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean held = false;
        while (!held && System.nanoTime() < deadline) {
          held = fixture.lockPresent(observer);
          if (!held) {
            Thread.sleep(20);
          }
        }
        assertTrue(held, "The native writer must hold the shared namespace boundary");
        var refusal =
            assertThrows(
                PhysicalOwnershipException.class,
                () -> PhysicalOwnershipSession.open(observer, fixture.name));
        assertEquals(PhysicalOwnershipException.Code.SESSION_BUSY, refusal.code());
        assertEquals(7, fixture.scalar("SELECT amount FROM ordinary WHERE id = 1"));
      } finally {
        rowOwner.rollback();
      }
      assertEquals(1, write.get(20, TimeUnit.SECONDS));
      assertEquals(9, fixture.scalar("SELECT amount FROM ordinary WHERE id = 1"));
      fixture.assertUnlocked();
    }
  }
}
