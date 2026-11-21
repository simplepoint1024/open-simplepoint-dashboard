package org.simplepoint.data.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalOwnershipActivationDatabaseTest.Fixture;

/** Native atomic adoption, uncertain commit recovery and mandatory separation from activation. */
class PhysicalOwnershipAdoptionDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void reviewedClaimsAndReceiptCommitTogetherWithoutRuntimeActivation(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var manifest = prepare(connection, postgres);
      assertFalse(PhysicalOwnershipInstaller.hasAdoptionReceipt(connection, manifest));
      var calls = new AtomicInteger();
      PhysicalOwnershipInstaller.AdoptionVerifier verify =
          (target, expected) -> {
            calls.incrementAndGet();
            assertTrue(target.getAutoCommit());
            try (var second = fixture.connect()) {
              assertEquals(
                  PhysicalOwnershipException.Code.SESSION_BUSY,
                  assertThrows(
                          PhysicalOwnershipException.class,
                          () ->
                              PhysicalOwnershipInstaller.inspect(
                                  second, manifest.target().namespace()))
                      .code());
            }
            try (var statement = target.createStatement();
                var rows = statement.executeQuery("SELECT id FROM legacy WHERE 1=0")) {
              assertFalse(rows.next());
            }
          };
      PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, verify);
      assertTrue(PhysicalOwnershipInstaller.hasAdoptionReceipt(connection, manifest));
      assertEquals(4, count(connection));
      PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, verify);
      assertEquals(4, count(connection));
      assertEquals(2, calls.get());
      assertEquals(
          PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED,
          assertThrows(
                  PhysicalOwnershipException.class,
                  () -> PhysicalOwnershipSession.open(connection, manifest.target().namespace()))
              .code());
      assertThrows(
          PhysicalOwnershipException.class,
          () ->
              PhysicalOwnershipInstaller.activateFresh(
                  connection, manifest.target().namespace(), manifest.namespaceId()));
      assertEquals(
          new PhysicalObjectOwner("dna-runtime", "tenant", "model"),
          PhysicalObjectClaims.owner(
                  connection,
                  PhysicalNamespace.resolve(connection, manifest.target().namespace()),
                  manifest.namespaceId(),
                  "legacy")
              .orElseThrow());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void changedReviewOrForeignOwnerCannotRewriteAnAdoptionReceipt(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var manifest = prepare(connection, postgres);
      PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {});
      var changed =
          new PhysicalAdoptionManifest(
              1,
              manifest.target(),
              manifest.namespaceId(),
              manifest.operation(),
              "b".repeat(64),
              manifest.claims());
      assertThrows(
          PhysicalOwnershipException.class,
          () -> PhysicalOwnershipInstaller.adoptReviewed(connection, changed, (c, m) -> {}));
      assertEquals(4, count(connection));
      var foreign =
          new PhysicalAdoptionManifest(
              1,
              manifest.target(),
              manifest.namespaceId(),
              UUID.randomUUID(),
              manifest.reviewFingerprint(),
              List.of(
                  new PhysicalAdoptionManifest.Claim(
                      "legacy",
                      new PhysicalObjectOwner("other", "tenant", "model"),
                      true,
                      "a".repeat(64))));
      assertThrows(
          PhysicalOwnershipException.class,
          () -> PhysicalOwnershipInstaller.adoptReviewed(connection, foreign, (c, m) -> {}));
      assertEquals(4, count(connection));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void partialClaimFailureRollsBackAllClaimsAndReceipt(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres)) {
      final PhysicalAdoptionManifest manifest;
      try (var connection = fixture.connect()) {
        manifest = prepare(connection, postgres);
        var writes = new AtomicInteger();
        Connection failing =
            (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      Object value = invoke(connection, method, args);
                      if (method.getName().equals("prepareStatement")
                          && args[0].toString().contains("VALUES (?,?,?,?,?,?,?,?,?)")) {
                        return Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (p, m, a) -> {
                              if (m.getName().equals("executeUpdate")
                                  && writes.incrementAndGet() == 2) {
                                throw new SQLException("Injected second claim failure");
                              }
                              return invoke(value, m, a);
                            });
                      }
                      return value;
                    });
        assertThrows(
            SQLException.class,
            () -> PhysicalOwnershipInstaller.adoptReviewed(failing, manifest, (c, m) -> {}));
      }
      try (var connection = fixture.connect()) {
        assertEquals(1, count(connection));
        PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {});
        assertEquals(4, count(connection));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostCommitReplyIsReconciledWithTheOriginalManifest(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres)) {
      final PhysicalAdoptionManifest manifest;
      try (var connection = fixture.connect()) {
        manifest = prepare(connection, postgres);
        Connection failing =
            (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      Object value = invoke(connection, method, args);
                      if (method.getName().equals("commit")) {
                        throw new SQLException("Injected lost commit reply");
                      }
                      return value;
                    });
        assertThrows(
            SQLException.class,
            () -> PhysicalOwnershipInstaller.adoptReviewed(failing, manifest, (c, m) -> {}));
      }
      try (var connection = fixture.connect()) {
        assertEquals(4, count(connection));
        assertTrue(PhysicalOwnershipInstaller.hasAdoptionReceipt(connection, manifest));
        PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {});
        assertEquals(4, count(connection));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void verifierFailureOrExistenceMismatchLeavesDirectoryUntouched(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var manifest = prepare(connection, postgres);
      assertThrows(
          SQLException.class,
          () ->
              PhysicalOwnershipInstaller.adoptReviewed(
                  connection,
                  manifest,
                  (c, m) -> {
                    throw new SQLException("Structure changed");
                  }));
      assertEquals(1, count(connection));
      assertTrue(
          PhysicalOwnershipInstaller.inspect(connection, manifest.target().namespace()).complete());
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE future_table(id INTEGER)");
      }
      assertThrows(
          PhysicalOwnershipException.class,
          () -> PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {}));
      assertEquals(1, count(connection));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void corruptedCompletedClaimIsNeverSilentlyRepaired(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var manifest = prepare(connection, postgres);
      PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {});
      try (var statement =
          connection.prepareStatement(
              "DELETE FROM simpoint_physical_object_owners_v1 WHERE entry_key=?")) {
        statement.setString(1, PhysicalObjectClaims.key("legacy"));
        statement.executeUpdate();
      }
      assertThrows(
          PhysicalOwnershipException.class,
          () -> PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {}));
      assertEquals(3, count(connection));
    }
  }

  static PhysicalAdoptionManifest prepare(Connection connection, boolean postgres)
      throws SQLException {
    String namespace = postgres ? connection.getSchema() : connection.getCatalog();
    UUID identity = UUID.randomUUID();
    PhysicalOwnershipInstaller.install(connection, namespace, identity);
    try (var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE legacy(id INTEGER)");
    }
    return new PhysicalAdoptionManifest(
        1,
        PhysicalNamespaceDescriptor.capture(connection, namespace),
        identity,
        UUID.randomUUID(),
        "a".repeat(64),
        List.of(
            new PhysicalAdoptionManifest.Claim(
                "legacy",
                new PhysicalObjectOwner("dna-runtime", "tenant", "model"),
                true,
                "a".repeat(64)),
            new PhysicalAdoptionManifest.Claim(
                "future_table",
                new PhysicalObjectOwner("dna-runtime", "tenant", "future"),
                false,
                "b".repeat(64))));
  }

  private static long count(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT COUNT(*) FROM simpoint_physical_object_owners_v1")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
