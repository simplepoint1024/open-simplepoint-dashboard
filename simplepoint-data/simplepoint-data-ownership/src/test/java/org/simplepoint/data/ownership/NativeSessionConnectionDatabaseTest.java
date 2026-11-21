package org.simplepoint.data.ownership;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeSessionConnectionDatabaseTest {
  @ParameterizedTest
  @ValueSource(strings = {"autoReconnect", "autoReconnectForPools", "reconnectAtTxEnd"})
  void effectivePropertiesAreCheckedEvenWhenNotPresentInTheUrl(String property) throws Exception {
    try (var fixture = new PhysicalOwnershipActivationDatabaseTest.Fixture(false);
        var installer = fixture.connect()) {
      String namespace = installer.getCatalog();
      install(installer, namespace);
      Properties properties = credentials(false);
      properties.setProperty(property, "true");
      try (var connection =
          DriverManager.getConnection(installer.getMetaData().getURL(), properties)) {
        rejectConfiguration(() -> PhysicalOwnershipSession.open(connection, namespace));
      }
      try (var session = PhysicalOwnershipSession.open(installer, namespace)) {
        session.verifyExecutionSession();
      }
    }
  }

  @Test
  void effectiveUrlConfigurationIsCheckedWithoutTrustingSeparateProperties()
      throws Exception {
    try (var fixture = new PhysicalOwnershipActivationDatabaseTest.Fixture(false);
        var installer = fixture.connect()) {
      String namespace = installer.getCatalog();
      install(installer, namespace);
      String url = installer.getMetaData().getURL();
      url += (url.contains("?") ? "&" : "?") + "autoReconnect=true";
      try (var connection = DriverManager.getConnection(url, credentials(false))) {
        rejectConfiguration(() -> PhysicalOwnershipSession.open(connection, namespace));
      }
    }
  }

  @Test
  void mysqlLoadBalancingCannotStandInForAnOwnedPhysicalConnection() throws Exception {
    try (var fixture = new PhysicalOwnershipActivationDatabaseTest.Fixture(false);
        var installer = fixture.connect()) {
      String namespace = installer.getCatalog();
      install(installer, namespace);
      String url =
          installer.getMetaData().getURL().replace("jdbc:mysql://", "jdbc:mysql:loadbalance://");
      try (var connection = DriverManager.getConnection(url, credentials(false))) {
        rejectConfiguration(() -> PhysicalOwnershipSession.open(connection, namespace));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void ordinaryHikariLeasesRetainTheirPhysicalSessionAcrossOwnedTransactions(boolean postgres)
      throws Exception {
    try (var fixture = new PhysicalOwnershipActivationDatabaseTest.Fixture(postgres);
        var installer = fixture.connect()) {
      String namespace = postgres ? installer.getSchema() : installer.getCatalog();
      install(installer, namespace);
      var config = new HikariConfig();
      config.setJdbcUrl(installer.getMetaData().getURL());
      config.setDataSourceProperties(credentials(postgres));
      config.setMaximumPoolSize(1);
      config.setMinimumIdle(0);
      try (var pool = new HikariDataSource(config);
          var connection = pool.getConnection();
          var session = PhysicalOwnershipSession.open(connection, namespace)) {
        connection.setAutoCommit(false);
        session.verifyExecutionSession();
        connection.commit();
        connection.setAutoCommit(true);
        session.verifyExecutionSession();
      }
    }
  }

  @Test
  void enablingReconnectAfterAdmissionAbortsTheLeaseInsteadOfReacquiringItsLock() throws Exception {
    try (var fixture = new PhysicalOwnershipActivationDatabaseTest.Fixture(false);
        var installer = fixture.connect();
        var connection = fixture.connect()) {
      String namespace = installer.getCatalog();
      install(installer, namespace);
      var session = PhysicalOwnershipSession.open(connection, namespace);
      Object properties = connection.getClass().getMethod("getPropertySet").invoke(connection);
      Object property =
          properties
              .getClass()
              .getMethod("getBooleanProperty", String.class)
              .invoke(properties, "autoReconnect");
      property.getClass().getMethod("setValue", Object.class).invoke(property, Boolean.TRUE);
      rejectConfiguration(session::verifyExecutionSession);
      assertTrue(connection.isClosed());
      assertThrows(SQLException.class, session::close);
      try (var subsequent = PhysicalOwnershipSession.open(installer, namespace)) {
        subsequent.verifyExecutionSession();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void terminatedServerSessionFailsWithoutTransparentRecovery(boolean postgres) throws Exception {
    try (var fixture = new PhysicalOwnershipActivationDatabaseTest.Fixture(postgres);
        var installer = fixture.connect();
        var connection = fixture.connect()) {
      String namespace = postgres ? installer.getSchema() : installer.getCatalog();
      install(installer, namespace);
      var session = PhysicalOwnershipSession.open(connection, namespace);
      long id;
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  postgres ? "SELECT pg_catalog.pg_backend_pid()" : "SELECT CONNECTION_ID()")) {
        assertTrue(rows.next());
        id = rows.getLong(1);
      }
      try (var statement = installer.createStatement()) {
        statement.setQueryTimeout(10);
        if (postgres) {
          try (var rows =
              statement.executeQuery("SELECT pg_catalog.pg_terminate_backend(" + id + ",5000)")) {
            assertTrue(rows.next());
            assertTrue(rows.getBoolean(1));
          }
        } else {
          statement.execute("KILL CONNECTION " + id);
        }
      }
      assertThrows(SQLException.class, session::verifyExecutionSession);
      assertThrows(SQLException.class, session::close);
      try (var subsequent = PhysicalOwnershipSession.open(installer, namespace)) {
        assertDoesNotThrow(subsequent::verifyExecutionSession);
      }
    }
  }

  private static void install(Connection connection, String namespace) throws SQLException {
    UUID identity = UUID.randomUUID();
    PhysicalOwnershipInstaller.install(connection, namespace, identity);
    PhysicalOwnershipInstaller.activateFresh(connection, namespace, identity);
  }

  private static Properties credentials(boolean postgres) {
    String prefix = "PHYSICAL_OWNERSHIP_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    var properties = new Properties();
    properties.setProperty("user", System.getenv(prefix + "_USER"));
    properties.setProperty("password", System.getenv(prefix + "_PASSWORD"));
    return properties;
  }

  private static void rejectConfiguration(org.junit.jupiter.api.function.Executable operation) {
    var failure = assertThrows(PhysicalOwnershipException.class, operation);
    assertEquals(PhysicalOwnershipException.Code.SESSION_CONFIGURATION_UNSUPPORTED, failure.code());
  }
}
