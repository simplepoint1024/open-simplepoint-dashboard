package org.simplepoint.data.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhysicalOwnershipActivationDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void installationReceiptDoesNotAuthorizeRuntimeUntilExplicitFreshActivation(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var absent = PhysicalOwnershipInstaller.inspect(connection, fixture.name);
      assertFalse(absent.complete());
      assertEquals(null, absent.namespaceId());
      UUID identity = UUID.randomUUID();
      PhysicalOwnershipInstaller.installReviewed(connection, absent.target(), identity);
      var installed = PhysicalOwnershipInstaller.inspect(connection, fixture.name);
      assertTrue(installed.complete());
      assertEquals(identity, installed.namespaceId());
      assertEquals(absent.target(), installed.target());
      reject(
          PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      PhysicalOwnershipInstaller.activateFresh(connection, fixture.name, identity);
      try (var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
        session.claimNew(
            "records", true, new PhysicalObjectOwner("dna-runtime", "tenant", "model"));
      }
      execute(connection, "CREATE TABLE records (id INTEGER)");
      PhysicalOwnershipInstaller.installReviewed(connection, absent.target(), identity);
      PhysicalOwnershipInstaller.activateFresh(connection, fixture.name, identity);
      try (var session = PhysicalOwnershipSession.open(connection, fixture.name)) {
        assertEquals("model", session.owner("records", true).orElseThrow().resourceId());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void changedNativeTargetOrProtocolIsRejectedBeforeDirectoryDdl(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      var target = PhysicalNamespaceDescriptor.capture(connection, fixture.name);
      var changed =
          new PhysicalNamespaceDescriptor(
              target.dialect(),
              "wrong-server",
              target.database(),
              target.databaseIdentity(),
              target.namespace(),
              target.namespaceIdentity(),
              target.protocolChecksum());
      reject(
          PhysicalOwnershipException.Code.INSTALLATION_CONFLICT,
          () -> PhysicalOwnershipInstaller.installReviewed(connection, changed, UUID.randomUUID()));
      assertEquals(
          null, PhysicalOwnershipInstaller.inspect(connection, fixture.name).namespaceId());
      var wrongProtocol =
          new PhysicalNamespaceDescriptor(
              target.dialect(),
              target.serverIdentity(),
              target.database(),
              target.databaseIdentity(),
              target.namespace(),
              target.namespaceIdentity(),
              "0".repeat(64));
      reject(
          PhysicalOwnershipException.Code.INSTALLATION_CONFLICT,
          () ->
              PhysicalOwnershipInstaller.installReviewed(
                  connection, wrongProtocol, UUID.randomUUID()));
      assertEquals(
          null, PhysicalOwnershipInstaller.inspect(connection, fixture.name).namespaceId());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void existingViewsAndRetainedClaimsCannotUseFreshActivation(boolean postgres) throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      PhysicalOwnershipInstaller.install(connection, fixture.name, identity);
      execute(connection, "CREATE VIEW legacy AS SELECT 1 AS id");
      reject(
          PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION,
          () -> PhysicalOwnershipInstaller.activateFresh(connection, fixture.name, identity));
      execute(connection, "DROP VIEW legacy");
      execute(
          connection,
          "INSERT INTO simpoint_physical_object_owners_v1 SELECT"
              + " 'legacy-claim',namespace_id,contract_version,schema_checksum,'missing',"
              + "'dna-runtime','tenant','model',created_at_ms"
              + " FROM simpoint_physical_object_owners_v1 WHERE entry_key='namespace'");
      reject(
          PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION,
          () -> PhysicalOwnershipInstaller.activateFresh(connection, fixture.name, identity));
      reject(
          PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void missingAndCorruptedActivationNeverBecomeImplicitPermission(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      PhysicalOwnershipInstaller.install(connection, fixture.name, identity);
      PhysicalOwnershipInstaller.activateFresh(connection, fixture.name, identity);
      execute(
          connection,
          "UPDATE simpoint_physical_object_owners_v1 SET owner_resource='corrupt'"
              + " WHERE entry_key='activation-v1'");
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
      reject(
          PhysicalOwnershipException.Code.DIRECTORY_INVALID,
          () -> PhysicalOwnershipInstaller.activateFresh(connection, fixture.name, identity));
      execute(
          connection,
          "DELETE FROM simpoint_physical_object_owners_v1 WHERE entry_key='activation-v1'");
      reject(
          PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED,
          () -> PhysicalOwnershipSession.open(connection, fixture.name));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void inspectionSeparatesIncompleteHeaderFromCompletedInstallation(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres);
        var connection = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      var expected = PhysicalNamespaceDescriptor.capture(connection, fixture.name);
      Connection interrupted =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")
                        && args[0] instanceof String sql
                        && sql.startsWith("INSERT INTO")) {
                      throw new SQLException("Injected header interruption");
                    }
                    try {
                      return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      assertThrows(
          SQLException.class,
          () -> PhysicalOwnershipInstaller.installReviewed(interrupted, expected, identity));
      try (var fresh = fixture.connect()) {
        var pending = PhysicalOwnershipInstaller.inspect(fresh, fixture.name);
        assertFalse(pending.complete());
        assertEquals(postgres ? null : identity, pending.namespaceId());
        PhysicalOwnershipInstaller.installReviewed(fresh, expected, identity);
        assertTrue(PhysicalOwnershipInstaller.inspect(fresh, fixture.name).complete());
        reject(
            PhysicalOwnershipException.Code.NAMESPACE_NOT_ACTIVATED,
            () -> PhysicalOwnershipSession.open(fresh, fixture.name));
      }
    }
  }

  @Test
  void mysqlTableOnlyVisibilityCannotCertifyAnEmptyNamespace() throws Exception {
    try (var fixture = new Fixture(false);
        var owner = fixture.connect()) {
      UUID identity = UUID.randomUUID();
      PhysicalOwnershipInstaller.install(owner, fixture.name, identity);
      execute(owner, "CREATE VIEW hidden_view AS SELECT 1 AS id");
      String name = "act_" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
      String user = "'" + name + "'@'%'";
      String password = UUID.randomUUID().toString();
      try (var statement = owner.prepareStatement("CREATE USER " + user + " IDENTIFIED BY ?")) {
        statement.setString(1, password);
        statement.execute();
      }
      try {
        execute(
            owner,
            "GRANT SELECT,TRIGGER ON "
                + fixture.name
                + ".simpoint_physical_object_owners_v1 TO "
                + user);
        URI endpoint = URI.create(fixture.url.substring(5));
        String url =
            "jdbc:"
                + new URI(
                    endpoint.getScheme(),
                    endpoint.getUserInfo(),
                    endpoint.getHost(),
                    endpoint.getPort(),
                    "/" + fixture.name,
                    endpoint.getQuery(),
                    endpoint.getFragment());
        try (var limited = DriverManager.getConnection(url, name, password)) {
          reject(
              PhysicalOwnershipException.Code.METADATA_UNAVAILABLE,
              () -> PhysicalOwnershipInstaller.activateFresh(limited, fixture.name, identity));
        }
        execute(owner, "GRANT TRIGGER ON " + fixture.name + ".* TO " + user);
        try (var limited = DriverManager.getConnection(url, name, password)) {
          reject(
              PhysicalOwnershipException.Code.EXISTING_OBJECT_REQUIRES_ADOPTION,
              () -> PhysicalOwnershipInstaller.activateFresh(limited, fixture.name, identity));
        }
      } finally {
        execute(owner, "DROP USER " + user);
      }
    }
  }

  @Test
  void mysqlColumnMetadataRequiresWholeTableSelectIncludingActiveRoles() throws Exception {
    try (var fixture = new Fixture(false);
        var owner = fixture.connect()) {
      execute(owner, "CREATE TABLE ordinary(id integer, hidden_value integer DEFAULT 7)");
      String name = "cols_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
      String user = "'" + name + "'@'%'";
      String role = "'" + name + "_role'@'%'";
      String password = UUID.randomUUID().toString();
      try (var statement = owner.prepareStatement("CREATE USER " + user + " IDENTIFIED BY ?")) {
        statement.setString(1, password);
        statement.execute();
      }
      boolean roleCreated = false;
      try {
        execute(owner, "GRANT SELECT(id),TRIGGER ON " + fixture.name + ".ordinary TO " + user);
        URI endpoint = URI.create(fixture.url.substring(5));
        String url =
            "jdbc:"
                + new URI(
                    endpoint.getScheme(),
                    endpoint.getUserInfo(),
                    endpoint.getHost(),
                    endpoint.getPort(),
                    "/" + fixture.name,
                    endpoint.getQuery(),
                    endpoint.getFragment());
        try (var limited = DriverManager.getConnection(url, name, password)) {
          try (var statement = limited.createStatement();
              var rows =
                  statement.executeQuery(
                      "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='"
                          + fixture.name
                          + "' AND TABLE_NAME='ordinary'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "A nonempty catalog can still hide a default column");
          }
          reject(
              PhysicalOwnershipException.Code.METADATA_UNAVAILABLE,
              () -> MysqlTableMetadataAccess.requireSelect(limited, fixture.name, "ordinary"));
        }
        execute(owner, "CREATE ROLE " + role);
        roleCreated = true;
        execute(owner, "GRANT SELECT ON " + fixture.name + ".ordinary TO " + role);
        execute(owner, "GRANT " + role + " TO " + user);
        try (var limited = DriverManager.getConnection(url, name, password)) {
          reject(
              PhysicalOwnershipException.Code.METADATA_UNAVAILABLE,
              () -> MysqlTableMetadataAccess.requireSelect(limited, fixture.name, "ordinary"));
          execute(limited, "SET ROLE ALL");
          MysqlTableMetadataAccess.requireSelect(limited, fixture.name, "ordinary");
          execute(owner, "REVOKE SELECT ON " + fixture.name + ".ordinary FROM " + role);
          reject(
              PhysicalOwnershipException.Code.METADATA_UNAVAILABLE,
              () -> MysqlTableMetadataAccess.requireSelect(limited, fixture.name, "ordinary"));
        }
      } finally {
        execute(owner, "DROP USER " + user);
        if (roleCreated) {
          execute(owner, "DROP ROLE " + role);
        }
      }
    }
  }

  private static void reject(
      PhysicalOwnershipException.Code code, org.junit.jupiter.api.function.Executable operation) {
    assertEquals(code, assertThrows(PhysicalOwnershipException.class, operation).code());
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      statement.execute(sql);
    }
  }

  static final class Fixture implements AutoCloseable {
    private final boolean postgres;
    private final String name = "activation_" + UUID.randomUUID().toString().replace("-", "");
    private final String url;
    private final String user;
    private final String password;

    Fixture(boolean postgres) throws SQLException {
      this.postgres = postgres;
      String prefix = "PHYSICAL_OWNERSHIP_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
      url = System.getenv(prefix + "_URL");
      user = System.getenv(prefix + "_USER");
      password = System.getenv(prefix + "_PASSWORD");
      Assumptions.assumeTrue(url != null, "Disposable native database required");
      try (var connection = DriverManager.getConnection(url, user, password)) {
        execute(connection, "CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + name);
      }
    }

    Connection connect() throws SQLException {
      var connection = DriverManager.getConnection(url, user, password);
      if (postgres) {
        connection.setSchema(name);
      } else {
        connection.setCatalog(name);
      }
      return connection;
    }

    @Override
    public void close() throws SQLException {
      try (var connection = DriverManager.getConnection(url, user, password)) {
        execute(
            connection,
            "DROP " + (postgres ? "SCHEMA " : "DATABASE ") + name + (postgres ? " CASCADE" : ""));
      }
    }
  }
}
