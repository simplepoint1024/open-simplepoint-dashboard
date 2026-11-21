package org.simplepoint.data.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Uses disposable, isolated physical namespaces; no control-plane mocks or in-memory SQL dialect.
 */
class PhysicalOwnershipDatabaseTest {
  private static final PhysicalObjectOwner OWNER =
      new PhysicalObjectOwner("dna-runtime", "tenant-one", "model-one");

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retiringOwnedTablesPreservesForeignTablesAndFencesAnExactRetry(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres); var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      installActive(connection, fixture.name, identity);
      var otherOwner = new PhysicalObjectOwner("dna-runtime", "tenant-two", "model-two");
      var records = new PhysicalOwnershipSession.OwnedTable("records", OWNER);
      String table = fixture.table("records");
      String other = fixture.table("other");
      try (var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
        session.claimNew("records", true, OWNER);
        session.claimNew("other", true, otherOwner);
        fixture.execute(connection, "CREATE TABLE " + table + " (id INTEGER PRIMARY KEY)");
        fixture.execute(connection, "CREATE TABLE " + other + " (id INTEGER PRIMARY KEY,"
            + " record_id INTEGER, CONSTRAINT record_fk FOREIGN KEY (record_id) REFERENCES " + table + "(id))");
        fixture.execute(connection, "INSERT INTO " + table + " VALUES (1)");
        fixture.execute(connection, "INSERT INTO " + other + " VALUES (1, 1)");
        reject(PhysicalOwnershipException.Code.OWNER_CONFLICT, () -> session.dropOwnedTables(identity,
            List.of(records, new PhysicalOwnershipSession.OwnedTable("other", OWNER))));
        assertEquals(OWNER, session.owner("records", true).orElseThrow());
        assertThrows(SQLException.class, () -> session.dropOwnedTables(identity, List.of(records)));
        fixture.execute(connection, "SELECT * FROM " + table);
        fixture.execute(connection, "SELECT * FROM " + other);
        reject(PhysicalOwnershipException.Code.OWNER_CONFLICT, () -> session.claimNew("records", true, OWNER));
        fixture.execute(connection, "ALTER TABLE " + other
            + (postgres ? " DROP CONSTRAINT record_fk" : " DROP FOREIGN KEY record_fk"));
        session.dropOwnedTables(identity, List.of(records));
        session.dropOwnedTables(identity, List.of(records));
        assertThrows(SQLException.class, () -> fixture.execute(connection, "SELECT * FROM " + table));
        assertEquals(otherOwner, session.owner("other", true).orElseThrow());
        fixture.execute(connection, "SELECT * FROM " + other + " WHERE id = 1");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aliasesShareDurableClaimsAndInstallationIdentity(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var first = fixture.connect();
        var alias = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      installActive(first, fixture.name, identity);
      installActive(alias, fixture.name, identity);
      reject(
          PhysicalOwnershipException.Code.INSTALLATION_CONFLICT,
          () -> installActive(alias, fixture.name, UUID.randomUUID()));
      try (var session = PhysicalOwnershipSession.open(first, fixture.name)) {
        assertEquals(identity, session.namespaceId());
        assertTrue(session.owner("records", true).isEmpty());
        session.claimNew("records", true, OWNER);
        session.claimNew("records", true, OWNER);
        reject(
            PhysicalOwnershipException.Code.OWNER_CONFLICT,
            () ->
                session.claimNew(
                    "records", true, new PhysicalObjectOwner("app-form", "tenant-two", "other")));
        reject(
            PhysicalOwnershipException.Code.SESSION_BUSY,
            () -> PhysicalOwnershipSession.open(alias, fixture.name));
        assertFalse(alias.isClosed());
      }
      try (var session = PhysicalOwnershipSession.open(alias, fixture.name)) {
        assertEquals(identity, session.namespaceId());
        assertEquals(OWNER, session.owner("records", true).orElseThrow());
        fixture.execute(
            alias, "CREATE TABLE " + fixture.table("records") + " (id INTEGER PRIMARY KEY)");
        session.claimNew("records", true, OWNER);
        fixture.execute(alias, "DROP TABLE " + fixture.table("records"));
        assertEquals(OWNER, session.owner("records", true).orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingOrUnmarkedDirectoryCannotBeSilentlyAdopted(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_NOT_INSTALLED,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      fixture.execute(
          connection,
          "CREATE TABLE " + fixture.table(PhysicalNamespace.DIRECTORY) + " (id INTEGER)");
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> installActive(connection, fixture.name, UUID.randomUUID()));
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      assertFalse(connection.isClosed());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void existingTablesViewsAndReservedDirectoryCannotBeClaimed(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      installActive(connection, fixture.name, UUID.randomUUID());
      fixture.execute(connection, "CREATE TABLE " + fixture.table("legacy") + " (id INTEGER)");
      fixture.execute(
          connection,
          "CREATE VIEW "
              + fixture.table("legacy_view")
              + " AS SELECT id FROM "
              + fixture.table("legacy"));
      try (var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
        for (String name : new String[] {"legacy", "legacy_view"}) {
          reject(
              PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION,
              () -> session.claimNew(name, true, OWNER));
          assertTrue(session.owner(name, true).isEmpty());
        }
        assertEquals(
            "data-ownership",
            session.owner(PhysicalNamespace.DIRECTORY, true).orElseThrow().moduleId());
        reject(
            PhysicalOwnershipException.Code.RESERVED_OBJECT,
            () -> session.claimNew(PhysicalNamespace.DIRECTORY, true, OWNER));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeIdentifierRulesDoNotAliasLongOrUnicodeNames(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      installActive(connection, fixture.name, UUID.randomUUID());
      try (var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
        String name = postgres ? "Ä客戶.取引" : "Ä客戶取引";
        session.claimNew(name, true, OWNER);
        fixture.execute(connection, "CREATE TABLE " + fixture.table(name) + " (id INTEGER)");
        assertEquals(OWNER, session.owner(name, true).orElseThrow());
        if (postgres) {
          session.claimNew("ÄBC", false, OWNER);
          fixture.execute(
              connection, "CREATE TABLE " + fixture.quote(fixture.name) + ".ÄBC (id INTEGER)");
          assertEquals(OWNER, session.owner("Äbc", true).orElseThrow());
          assertTrue(session.owner("äbc", true).isEmpty());
          reject(
              PhysicalOwnershipException.Code.INVALID_OBJECT_NAME,
              () -> session.claimNew("界".repeat(22), true, OWNER));
        }
        reject(
            PhysicalOwnershipException.Code.INVALID_OBJECT_NAME,
            () -> session.claimNew("x".repeat(65), true, OWNER));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void invalidHeaderAndPhysicalDriftAreNotRepaired(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      installActive(connection, fixture.name, identity);
      fixture.execute(
          connection,
          "UPDATE " + fixture.table(PhysicalNamespace.DIRECTORY) + " SET contract_version=999");
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> installActive(connection, fixture.name, identity));
    }
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      installActive(connection, fixture.name, identity);
      fixture.execute(
          connection,
          "ALTER TABLE " + fixture.table(PhysicalNamespace.DIRECTORY) + " ADD unexpected INTEGER");
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> installActive(connection, fixture.name, identity));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unverifiedDirectoryStorageEngineCannotCarryOwnershipClaims(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      installActive(connection, fixture.name, identity);
      String method = fixture.quote(fixture.name);
      if (postgres) {
        fixture.execute(
            connection,
            "CREATE ACCESS METHOD "
                + method
                + " TYPE TABLE HANDLER pg_catalog.heap_tableam_handler");
      }
      try {
        fixture.execute(
            connection,
            "ALTER TABLE "
                + fixture.table(PhysicalNamespace.DIRECTORY)
                + (postgres ? " SET ACCESS METHOD " + method : " ENGINE=MyISAM"));
        reject(
            PhysicalOwnershipException.Code.DIRECTORY_INVALID,
            () -> PhysicalOwnershipSession.open(connection, fixture.name));
        reject(
            PhysicalOwnershipException.Code.DIRECTORY_INVALID,
            () -> installActive(connection, fixture.name, identity));
      } finally {
        fixture.execute(
            connection,
            "ALTER TABLE "
                + fixture.table(PhysicalNamespace.DIRECTORY)
                + (postgres ? " SET ACCESS METHOD heap" : " ENGINE=InnoDB"));
        if (postgres) {
          fixture.execute(connection, "DROP ACCESS METHOD " + method);
        }
      }
      try (var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
        assertEquals(identity, session.namespaceId());
        assertTrue(session.owner("records", true).isEmpty());
        session.claimNew("records", true, OWNER);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lockLossAbortsInsteadOfReacquiring(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect();
        var observer = fixture.connect()) {
      installActive(connection, fixture.name, UUID.randomUUID());
      var session = PhysicalOwnershipSession.open(connection, fixture.name);
      fixture.execute(
          connection, postgres ? "SELECT pg_advisory_unlock_all()" : "SELECT RELEASE_ALL_LOCKS()");
      reject(
          PhysicalOwnershipException.Code.SESSION_STATE_INVALID, session::verifyExecutionSession);
      assertTrue(connection.isClosed());
      assertThrows(SQLException.class, session::close);
      try (var next = PhysicalOwnershipSession.open(observer, fixture.name)) {
        next.verifyExecutionSession();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void namespaceLockSurvivesTransactionsAndCloseRejectsAnOpenTransaction(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect();
        var observer = fixture.connect()) {
      installActive(connection, fixture.name, UUID.randomUUID());
      connection.setAutoCommit(false);
      reject(
          PhysicalOwnershipException.Code.SESSION_STATE_INVALID,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      connection.rollback();
      connection.setAutoCommit(true);
      var session = PhysicalOwnershipSession.open(connection, fixture.name);
      session.claimNew("records", true, OWNER);
      fixture.execute(
          connection, "CREATE TABLE " + fixture.table("records") + " (id INTEGER PRIMARY KEY)");
      connection.setAutoCommit(false);
      fixture.execute(connection, "INSERT INTO " + fixture.table("records") + " VALUES (1)");
      session.verifyExecutionSession();
      reject(
          PhysicalOwnershipException.Code.SESSION_BUSY,
          () -> PhysicalOwnershipSession.open(observer, fixture.name));
      reject(
          PhysicalOwnershipException.Code.SESSION_STATE_INVALID,
          () -> session.claimNew("other", true, OWNER));
      reject(PhysicalOwnershipException.Code.SESSION_STATE_INVALID, session::close);
      assertTrue(connection.isClosed());
      try (var next = awaitReleased(observer, fixture.name);
          var statement = observer.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM " + fixture.table("records"))) {
        next.verifyExecutionSession();
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1));
        assertEquals(OWNER, next.owner("records", true).orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void interruptedInstallationReconcilesUsingThePersistedIdentity(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      Connection interrupted =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")
                        && args[0] instanceof String sql
                        && sql.startsWith("INSERT INTO")) {
                      throw new SQLException("Injected failure before installation header");
                    }
                    try {
                      return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      assertThrows(SQLException.class, () -> installActive(interrupted, fixture.name, identity));
      try (var fresh = fixture.connect()) {
        var namespace = PhysicalNamespace.resolve(fresh, fixture.name);
        assertEquals(
            postgres ? null : identity, OwnershipSchema.installedIdentity(fresh, namespace));
        if (!postgres) {
          reject(
              PhysicalOwnershipException.Code.DIRECTORY_INVALID,
              () -> PhysicalOwnershipSession.open(fresh, fixture.name));
          reject(
              PhysicalOwnershipException.Code.INSTALLATION_CONFLICT,
              () -> installActive(fresh, fixture.name, UUID.randomUUID()));
        }
        installActive(fresh, fixture.name, identity);
        try (var session = PhysicalOwnershipSession.open(fresh, fixture.name)) {
          assertEquals(identity, session.namespaceId());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void independentNamespacesDoNotShareLocksOrClaims(boolean postgres) throws Exception {
    try (var left = new Fixture(postgres);
        var right = new Fixture(postgres);
        var first = left.connect();
        var second = right.connect()) {
      installActive(first, left.name, UUID.randomUUID());
      installActive(second, right.name, UUID.randomUUID());
      try (var one = PhysicalOwnershipSession.open(first, left.name);
          var two = PhysicalOwnershipSession.open(second, right.name)) {
        one.claimNew("records", true, OWNER);
        assertTrue(two.owner("records", true).isEmpty());
        two.claimNew("records", true, new PhysicalObjectOwner("app-form", null, "another-model"));
        assertEquals(OWNER, one.owner("records", true).orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostLockReceiptsDiscardTheExecutingConnection(boolean postgres) throws Exception {
    for (boolean acquire : new boolean[] {true, false}) {
      try (var fixture = new Fixture(postgres);
          var connection = fixture.connect();
          var observer = fixture.connect()) {
        installActive(connection, fixture.name, UUID.randomUUID());
        String function =
            postgres
                ? (acquire ? "pg_try_advisory_lock" : "pg_advisory_unlock")
                : (acquire ? "GET_LOCK" : "RELEASE_LOCK");
        Connection interrupted =
            (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      try {
                        Object result = method.invoke(connection, args);
                        if (method.getName().equals("prepareStatement")
                            && ((String) args[0]).contains(function)) {
                          var actual = (java.sql.PreparedStatement) result;
                          return Proxy.newProxyInstance(
                              java.sql.PreparedStatement.class.getClassLoader(),
                              new Class<?>[] {java.sql.PreparedStatement.class},
                              (ignored, call, values) -> {
                                try {
                                  Object receipt = call.invoke(actual, values);
                                  if (call.getName().equals("executeQuery")) {
                                    ((java.sql.ResultSet) receipt).close();
                                    throw new SQLException(
                                        "Injected loss of completed lock receipt");
                                  }
                                  return receipt;
                                } catch (InvocationTargetException failure) {
                                  throw failure.getCause();
                                }
                              });
                        }
                        return result;
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        if (acquire) {
          assertThrows(
              SQLException.class, () -> PhysicalOwnershipSession.open(interrupted, fixture.name));
        } else {
          var session = PhysicalOwnershipSession.open(interrupted, fixture.name);
          assertThrows(SQLException.class, session::close);
        }
        assertTrue(connection.isClosed());
        try (var replacement = awaitReleased(observer, fixture.name)) {
          replacement.verifyExecutionSession();
        }
      }
    }
  }

  @org.junit.jupiter.api.Test
  void failedRollbackAndAbortNeverRestoreAutocommitOrCommitPartialInstallation() throws Exception {
    try (var fixture = new Fixture(true);
        var connection = fixture.connect()) {
      var restored = new java.util.concurrent.atomic.AtomicBoolean();
      Connection failing =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")
                        && ((String) args[0]).startsWith("INSERT INTO")) {
                      throw new SQLException("Injected header failure");
                    }
                    if (method.getName().equals("rollback") || method.getName().equals("abort")) {
                      throw new SQLException("Injected connection cleanup failure");
                    }
                    if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                      restored.set(true);
                    }
                    try {
                      return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      var failure =
          assertThrows(
              SQLException.class, () -> installActive(failing, fixture.name, UUID.randomUUID()));
      assertEquals("Injected header failure", failure.getMessage());
      assertTrue(failure.getSuppressed().length >= 2);
      assertFalse(restored.get());
      assertFalse(connection.getAutoCommit());
      // The caller must discard a connection whose abort also failed, never return it to a pool.
      connection.rollback();
      connection.setAutoCommit(true);
      try (var observer = fixture.connect()) {
        assertEquals(
            null,
            OwnershipSchema.installedIdentity(
                observer, PhysicalNamespace.resolve(observer, fixture.name)));
      }
    }
  }

  @org.junit.jupiter.api.Test
  void inheritedDirectoryAndMissingAtomicHeaderAreRejected() throws Exception {
    try (var fixture = new Fixture(true);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      installActive(connection, fixture.name, identity);
      fixture.execute(
          connection,
          "CREATE TABLE "
              + fixture.table("inherited")
              + " () INHERITS ("
              + fixture.table(PhysicalNamespace.DIRECTORY)
              + ")");
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      fixture.execute(connection, "DROP TABLE " + fixture.table("inherited"));
      fixture.execute(connection, "DELETE FROM " + fixture.table(PhysicalNamespace.DIRECTORY));
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> installActive(connection, fixture.name, identity));
    }
  }

  @org.junit.jupiter.api.Test
  void postgresSearchPathCannotShadowNativeLockFunctions() throws Exception {
    try (var fixture = new Fixture(true);
        var connection = fixture.connect();
        var observer = fixture.connect()) {
      fixture.execute(
          connection,
          "CREATE FUNCTION "
              + fixture.table("pg_try_advisory_lock")
              + "(bigint) RETURNS boolean LANGUAGE SQL AS 'SELECT false'");
      fixture.execute(
          connection,
          "CREATE FUNCTION "
              + fixture.table("pg_advisory_unlock")
              + "(bigint) RETURNS boolean LANGUAGE SQL AS 'SELECT false'");
      fixture.execute(
          connection,
          "CREATE FUNCTION "
              + fixture.table("pg_backend_pid")
              + "() RETURNS integer LANGUAGE SQL AS 'SELECT 0'");
      fixture.execute(
          connection, "SET search_path TO " + fixture.quote(fixture.name) + ", pg_catalog");
      installActive(connection, fixture.name, UUID.randomUUID());
      try (var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
        session.claimNew("records", true, OWNER);
        session.verifyExecutionSession();
        reject(
            PhysicalOwnershipException.Code.SESSION_BUSY,
            () -> PhysicalOwnershipSession.open(observer, fixture.name));
      }
      try (var session = PhysicalOwnershipSession.open(observer, fixture.name)) {
        assertEquals(OWNER, session.owner("records", true).orElseThrow());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void absentObjectReviewHoldsNamespaceExclusionAndRejectsViews(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect();
        var observer = fixture.connect()) {
      var target = PhysicalNamespaceDescriptor.capture(connection, fixture.name);
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      PhysicalOwnershipInstaller.withAbsentReviewed(
          connection,
          target,
          java.util.List.of("retired"),
          () -> {
            calls.incrementAndGet();
            reject(
                PhysicalOwnershipException.Code.SESSION_BUSY,
                () ->
                    PhysicalOwnershipInstaller.install(observer, fixture.name, UUID.randomUUID()));
          });
      assertEquals(1, calls.get());
      fixture.execute(connection, "CREATE VIEW " + fixture.table("retired") + " AS SELECT 1 AS id");
      reject(
          PhysicalOwnershipException.Code.INSTALLATION_CONFLICT,
          () ->
              PhysicalOwnershipInstaller.withAbsentReviewed(
                  connection, target, java.util.List.of("retired"), calls::incrementAndGet));
      assertEquals(1, calls.get());
    }
  }

  private static void installActive(Connection connection, String namespace, UUID identity)
      throws SQLException {
    PhysicalOwnershipInstaller.install(connection, namespace, identity);
    PhysicalOwnershipInstaller.activateFresh(connection, namespace, identity);
  }

  private static void reject(
      PhysicalOwnershipException.Code code, org.junit.jupiter.api.function.Executable operation) {
    assertEquals(code, assertThrows(PhysicalOwnershipException.class, operation).code());
  }

  private static PhysicalOwnershipSession awaitReleased(Connection observer, String namespace)
      throws Exception {
    // Driver abort closes the socket; the server may still be rolling back before releasing locks.
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (true) {
      try {
        return PhysicalOwnershipSession.open(observer, namespace);
      } catch (PhysicalOwnershipException failure) {
        if (failure.code() != PhysicalOwnershipException.Code.SESSION_BUSY
            || System.nanoTime() >= deadline) {
          throw failure;
        }
        Thread.sleep(20);
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final boolean postgres;
    private final String prefix;
    private final String name = "ownership_" + UUID.randomUUID().toString().replace("-", "");

    private Fixture(boolean postgres) throws SQLException {
      this.postgres = postgres;
      prefix = "PHYSICAL_OWNERSHIP_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
      Assumptions.assumeTrue(
          System.getenv(prefix + "_URL") != null, "Disposable database is required");
      try (var connection = connect()) {
        execute(connection, "CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + quote(name));
      }
    }

    private Connection connect() throws SQLException {
      return DriverManager.getConnection(
          System.getenv(prefix + "_URL"),
          System.getenv(prefix + "_USER"),
          System.getenv(prefix + "_PASSWORD"));
    }

    private String quote(String value) {
      String delimiter = postgres ? "\"" : "`";
      return delimiter + value.replace(delimiter, delimiter + delimiter) + delimiter;
    }

    private String table(String table) {
      return quote(name) + "." + quote(table);
    }

    private void execute(Connection connection, String sql) throws SQLException {
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        statement.execute(sql);
      }
    }

    @Override
    public void close() throws SQLException {
      try (var connection = connect()) {
        execute(
            connection,
            "DROP "
                + (postgres ? "SCHEMA " : "DATABASE ")
                + quote(name)
                + (postgres ? " CASCADE" : ""));
      }
    }
  }
}
