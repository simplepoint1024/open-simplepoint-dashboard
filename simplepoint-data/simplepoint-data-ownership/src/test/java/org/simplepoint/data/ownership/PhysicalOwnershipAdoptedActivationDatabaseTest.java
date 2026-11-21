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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.ownership.PhysicalOwnershipActivationDatabaseTest.Fixture;

/**
 * Final activation is bound to exact adoption evidence and remains recoverable after lost replies.
 */
class PhysicalOwnershipAdoptedActivationDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void activationRequiresAdoptionAndSeparatesFreshNamespaces(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var manifest = PhysicalOwnershipAdoptionDatabaseTest.prepare(connection, postgres);
      assertFalse(PhysicalOwnershipInstaller.hasActivationReceipt(connection, manifest));
      assertThrows(
          PhysicalOwnershipException.class,
          () -> PhysicalOwnershipInstaller.activateAdopted(connection, manifest, (c, m) -> {}));
      PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {});
      assertFalse(PhysicalOwnershipInstaller.hasActivationReceipt(connection, manifest));
      PhysicalOwnershipInstaller.activateAdopted(connection, manifest, (c, m) -> {});
      assertTrue(PhysicalOwnershipInstaller.hasActivationReceipt(connection, manifest));
      try (var session = PhysicalOwnershipSession.open(connection, manifest.target().namespace())) {
        assertEquals(
            manifest.claims().stream()
                .filter(c -> c.table().equals("legacy"))
                .findFirst()
                .orElseThrow()
                .owner(),
            session.owner("legacy", true).orElseThrow());
      }
      assertThrows(
          PhysicalOwnershipException.class,
          () ->
              PhysicalOwnershipInstaller.activateFresh(
                  connection, manifest.target().namespace(), manifest.namespaceId()));
      // Completed activation is immutable even when a runtime later legitimately changes its table.
      PhysicalOwnershipInstaller.activateAdopted(
          connection,
          manifest,
          (c, m) -> {
            throw new AssertionError(
                "Completed activation must not repeat structural writes or checks");
          });
      var changed =
          new PhysicalAdoptionManifest(
              1,
              manifest.target(),
              manifest.namespaceId(),
              manifest.operation(),
              "c".repeat(64),
              manifest.claims());
      assertThrows(
          PhysicalOwnershipException.class,
          () -> PhysicalOwnershipInstaller.hasActivationReceipt(connection, changed));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void finalStructureFailureLeavesNamespaceInactive(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var manifest = PhysicalOwnershipAdoptionDatabaseTest.prepare(connection, postgres);
      PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {});
      assertThrows(
          SQLException.class,
          () ->
              PhysicalOwnershipInstaller.activateAdopted(
                  connection,
                  manifest,
                  (c, m) -> {
                    throw new SQLException("injected structure mismatch");
                  }));
      assertFalse(PhysicalOwnershipInstaller.hasActivationReceipt(connection, manifest));
      assertEquals(
          PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED,
          assertThrows(
                  PhysicalOwnershipException.class,
                  () -> PhysicalOwnershipSession.open(connection, manifest.target().namespace()))
              .code());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lostActivationReplyCanBeResolvedWithoutRepeatingActivation(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      final PhysicalAdoptionManifest manifest;
      try (var connection = fixture.connect()) {
        manifest = PhysicalOwnershipAdoptionDatabaseTest.prepare(connection, postgres);
        PhysicalOwnershipInstaller.adoptReviewed(connection, manifest, (c, m) -> {});
        Connection uncertain =
            (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      Object result = invoke(connection, method, args);
                      if (method.getName().equals("prepareStatement")
                          && args[0].toString().startsWith("INSERT INTO")) {
                        var statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (p, m, a) -> {
                              Object value = invoke(statement, m, a);
                              if (m.getName().equals("executeUpdate")) {
                                throw new SQLException("injected lost activation reply", "08006");
                              }
                              return value;
                            });
                      }
                      return result;
                    });
        assertThrows(
            SQLException.class,
            () -> PhysicalOwnershipInstaller.activateAdopted(uncertain, manifest, (c, m) -> {}));
      }
      try (var fresh = fixture.connect()) {
        assertTrue(PhysicalOwnershipInstaller.hasActivationReceipt(fresh, manifest));
        try (var session = PhysicalOwnershipSession.open(fresh, manifest.target().namespace())) {
          assertTrue(session.owner("legacy", true).isPresent());
        }
      }
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
