package org.simplepoint.platform.installation.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.migration.ModuleMigrationEngine;
import org.simplepoint.data.ownership.PhysicalNamespaceDescriptor;
import org.simplepoint.data.ownership.PhysicalOwnershipException;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.platform.installation.InstallationException;
import org.simplepoint.platform.installation.InstallationFingerprint;
import org.simplepoint.platform.installation.InstallationJournal.State;
import org.simplepoint.platform.installation.InstallationRunner;
import org.simplepoint.platform.installation.jdbc.InstallationJournalMigrations;
import org.simplepoint.platform.installation.jdbc.JdbcInstallationJournal;
import org.springframework.jdbc.datasource.AbstractDataSource;

class PhysicalNamespaceInstallationDatabaseTest {
  private static final String RELEASE = InstallationFingerprint.of("reviewed-test-release");

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingJournalAcknowledgementReconcilesExactPersistedNamespaceIdentity(boolean postgres)
      throws Exception {
    try (var target = new InstallationDatabaseFixture(postgres);
        var control = new InstallationDatabaseFixture(true)) {
      var journal = journal(control);
      var reviewed = target(target, "business");
      var prepared =
          prepare(UUID.randomUUID(), List.of(reviewed), Map.of("business", target.source));
      journal.open(prepared.plan());
      journal.claim(prepared.plan(), 0);
      assertEquals(null, inspection(target).namespaceId());
      final String receipt = prepared.actions().getFirst().apply();
      assertEquals(reviewed.namespaceId(), inspection(target).namespaceId());
      assertEquals(State.RUNNING, journal.read(prepared.plan()).steps().getFirst().state());
      var restarted =
          prepare(
              prepared.plan().operationId(), List.of(reviewed), Map.of("business", target.source));
      assertTrue(
          new InstallationRunner(new JdbcInstallationJournal(control.source))
              .run(restarted.plan(), restarted.actions())
              .complete());
      restarted.verifyComplete();
      assertEquals(receipt, journal.read(prepared.plan()).steps().getFirst().receiptFingerprint());
      assertEquals(1, control.scalar("SELECT COUNT(*) FROM simpoint_installation_attempts"));
      try (var connection = target.source.getConnection()) {
        assertEquals(
            PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED,
            assertThrows(
                    PhysicalOwnershipException.class,
                    () -> PhysicalOwnershipSession.open(connection, target.name))
                .code());
      }
      target.execute("DROP TABLE simpoint_physical_object_owners_v1");
      assertTrue(
          new InstallationRunner(journal).run(prepared.plan(), prepared.actions()).complete());
      assertThrows(IllegalStateException.class, prepared::verifyComplete);
      assertEquals(null, inspection(target).namespaceId());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void failedHeaderResumesSamePlanWithoutReplacingNamespaceIdentity(boolean postgres)
      throws Exception {
    try (var target = new InstallationDatabaseFixture(postgres);
        var control = new InstallationDatabaseFixture(true)) {
      var journal = journal(control);
      var failOnce = new AtomicBoolean(true);
      var source = interrupted(target.source, failOnce);
      var reviewed = target(target, "business");
      var prepared = prepare(UUID.randomUUID(), List.of(reviewed), Map.of("business", source));
      assertThrows(
          InstallationException.class,
          () -> new InstallationRunner(journal).run(prepared.plan(), prepared.actions()));
      assertEquals(State.FAILED, journal.read(prepared.plan()).steps().getFirst().state());
      assertFalse(inspection(target).complete());
      assertEquals(postgres ? null : reviewed.namespaceId(), inspection(target).namespaceId());
      assertTrue(prepared.actions().getFirst().completedReceipt().isEmpty());
      var restarted =
          prepare(
              prepared.plan().operationId(), List.of(reviewed), Map.of("business", target.source));
      assertTrue(
          new InstallationRunner(journal).run(restarted.plan(), restarted.actions()).complete());
      restarted.verifyComplete();
      assertEquals(reviewed.namespaceId(), inspection(target).namespaceId());
      assertEquals(2, control.scalar("SELECT COUNT(*) FROM simpoint_installation_attempts"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void uncertainRunningAttemptCannotBeBlindlyReexecuted(boolean postgres) throws Exception {
    try (var target = new InstallationDatabaseFixture(postgres);
        var control = new InstallationDatabaseFixture(true)) {
      var journal = journal(control);
      var prepared =
          prepare(
              UUID.randomUUID(),
              List.of(target(target, "business")),
              Map.of("business", target.source));
      journal.open(prepared.plan());
      var attempt = journal.claim(prepared.plan(), 0);
      var failure =
          assertThrows(
              InstallationException.class,
              () -> new InstallationRunner(journal).run(prepared.plan(), prepared.actions()));
      assertEquals(InstallationException.Code.RECOVERY_REQUIRED, failure.code());
      assertEquals(null, inspection(target).namespaceId());
      assertEquals(attempt.token(), journal.read(prepared.plan()).steps().getFirst().attempt());
      // There is no executing worker in this fixture; explicitly authorize its retired attempt.
      journal.authorizeRetry(
          prepared.plan(), attempt, InstallationFingerprint.of("fixture-worker-retired"));
      assertTrue(
          new InstallationRunner(journal).run(prepared.plan(), prepared.actions()).complete());
      prepared.verifyComplete();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void preflightRejectsChangedTargetsAndAliasesBeforeAnyDirectoryMutation(boolean postgres)
      throws Exception {
    try (var first = new InstallationDatabaseFixture(postgres);
        var second = new InstallationDatabaseFixture(postgres)) {
      var one = target(first, "first");
      var two = target(second, "second");
      var d = two.descriptor();
      var changed =
          new PhysicalNamespaceDescriptor(
              d.dialect(),
              "changed-server",
              d.database(),
              d.databaseIdentity(),
              d.namespace(),
              d.namespaceIdentity(),
              d.protocolChecksum());
      assertThrows(
          IllegalStateException.class,
          () ->
              prepare(
                  UUID.randomUUID(),
                  List.of(
                      one,
                      new PhysicalNamespaceInstallation.Target(
                          "second", changed, two.namespaceId())),
                  Map.of("first", first.source, "second", second.source)));
      assertEquals(null, inspection(first).namespaceId());
      assertEquals(null, inspection(second).namespaceId());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              prepare(
                  UUID.randomUUID(),
                  List.of(
                      one,
                      new PhysicalNamespaceInstallation.Target(
                          "alias", one.descriptor(), UUID.randomUUID())),
                  Map.of("first", first.source, "alias", first.source)));
      var prepared = prepare(UUID.randomUUID(), List.of(one), Map.of("first", first.source));
      UUID foreignIdentity = UUID.randomUUID();
      try (var connection = first.source.getConnection()) {
        PhysicalOwnershipInstaller.install(connection, first.name, foreignIdentity);
      }
      assertThrows(IllegalStateException.class, () -> prepared.actions().getFirst().apply());
      assertThrows(
          IllegalStateException.class, () -> prepared.actions().getFirst().completedReceipt());
      assertEquals(foreignIdentity, inspection(first).namespaceId());
    }
  }

  @Test
  void jdbcFailureCannotExposeCredentialBearingDriverMessage() {
    var descriptor =
        new PhysicalNamespaceDescriptor(
            "mysql", "server", "database", "database", "database", "database", "0".repeat(64));
    var source =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            throw new SQLException("jdbc:mysql://private/?password=must-not-escape", "08001", 1045);
          }

          @Override
          public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
          }
        };
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                prepare(
                    UUID.randomUUID(),
                    List.of(
                        new PhysicalNamespaceInstallation.Target(
                            "business", descriptor, UUID.randomUUID())),
                    Map.of("business", source)));
    for (Throwable item = failure; item != null; item = item.getCause()) {
      assertFalse(item.toString().contains("must-not-escape"));
    }
    assertEquals("08001", ((SQLException) failure.getCause()).getSQLState());
    var reviewFailure =
        assertThrows(
            IllegalStateException.class,
            () ->
                PhysicalNamespaceInstallation.review(
                    "business", source, "database", UUID.randomUUID()));
    for (Throwable item = reviewFailure; item != null; item = item.getCause()) {
      assertFalse(item.toString().contains("must-not-escape"));
    }
    assertEquals("08001", ((SQLException) reviewFailure.getCause()).getSQLState());
  }

  private static PhysicalNamespaceInstallation.Prepared prepare(
      UUID operation,
      List<PhysicalNamespaceInstallation.Target> targets,
      Map<String, DataSource> sources) {
    return PhysicalNamespaceInstallation.prepare(
        operation, "physical/test", 0, RELEASE, targets, sources);
  }

  private static PhysicalNamespaceInstallation.Target target(
      InstallationDatabaseFixture fixture, String key) throws SQLException {
    try (var connection = fixture.source.getConnection()) {
      return new PhysicalNamespaceInstallation.Target(
          key, PhysicalNamespaceDescriptor.capture(connection, fixture.name), UUID.randomUUID());
    }
  }

  private static org.simplepoint.data.ownership.PhysicalOwnershipInspection inspection(
      InstallationDatabaseFixture fixture) throws SQLException {
    try (var connection = fixture.source.getConnection()) {
      return PhysicalOwnershipInstaller.inspect(connection, fixture.name);
    }
  }

  private static JdbcInstallationJournal journal(InstallationDatabaseFixture control) {
    new ModuleMigrationEngine()
        .migrate(InstallationJournalMigrations.plan("journal"), Map.of("journal", control.source));
    return new JdbcInstallationJournal(control.source);
  }

  private static DataSource interrupted(DataSource source, AtomicBoolean failOnce) {
    return new AbstractDataSource() {
      @Override
      public Connection getConnection() throws SQLException {
        var connection = source.getConnection();
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("prepareStatement")
                      && args[0] instanceof String sql
                      && sql.startsWith("INSERT INTO")
                      && failOnce.getAndSet(false)) {
                    throw new SQLException("Injected header interruption");
                  }
                  try {
                    return method.invoke(connection, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Unexpected credential override");
      }
    };
  }
}
