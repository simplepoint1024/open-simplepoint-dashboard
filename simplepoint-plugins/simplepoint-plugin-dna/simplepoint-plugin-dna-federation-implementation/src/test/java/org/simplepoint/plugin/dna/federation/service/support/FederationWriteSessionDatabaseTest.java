package org.simplepoint.plugin.dna.federation.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.CalciteWriteAdmission;
import org.simplepoint.data.calcite.core.query.CalciteWritePlan;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Physical session locks and uncommitted writes must survive all Calcite JDBC borrows. */
class FederationWriteSessionDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void calciteUsesTheLockedOwnerConnectionOnRealDatabases(boolean postgres) throws Exception {
    String prefix = "DNA_FEDERATION_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable real database is required");
    var pool =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    String table = "write_session_" + UUID.randomUUID().toString().replace("-", "");
    var acquired = new AtomicInteger();
    var released = new AtomicInteger();
    final var tracked =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            acquired.incrementAndGet();
            var physical = pool.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("close")) {
                        released.incrementAndGet();
                      }
                      try {
                        return method.invoke(physical, args);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
          }

          @Override
          public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLException("Unexpected credential change");
          }
        };
    final var sources = mock(JdbcDataSourceDefinitionService.class);
    final var drivers = mock(JdbcDriverDefinitionService.class);
    final var dialects = mock(JdbcDialectManagementService.class);
    var definition = new JdbcDataSourceDefinition();
    definition.setId("physical");
    definition.setCode("ds");
    definition.setDriverId("driver");
    definition.setJdbcUrl(url);
    var driver = new JdbcDriverDefinition();
    driver.setDatabaseType(postgres ? "postgresql" : "mysql");
    driver.setDriverClassName(postgres ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver");
    JdbcDatabaseDialect dialect =
        postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    when(sources.requireSimpleDataSource("physical")).thenReturn(new SimpleDataSource(tracked));
    when(drivers.findActiveById("driver")).thenReturn(Optional.of(driver));
    when(dialects.resolveDialect(any())).thenReturn(Optional.of(dialect));
    var assembler = new FederationCalciteCatalogAssembler(sources, drivers, dialects);
    long lockId = UUID.randomUUID().getMostSignificantBits();
    try (var observer = pool.getConnection();
        var statement = observer.createStatement()) {
      statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY)");
      try {
        try (var assembly = assembler.assembleForWrite("ds", List.of(definition), Map.of())) {
          assertEquals(1, assembly.writeConnections().size());
          var owner = assembly.writeConnections().getFirst();
          assertTrue(lock(owner, postgres, lockId, true));
          owner.setAutoCommit(false);
          var denied = new AtomicBoolean();
          var admissions = new AtomicInteger();
          var refusal = new IllegalStateException("fixed-write-admission-rejection");
          final long ownedSession = sessionId(owner, postgres);
          var delegate = assembly.schemaConfigurer();
          var schemas =
              new CalciteSchemaConfigurer() {
                @Override
                public void configure(org.apache.calcite.schema.SchemaPlus root) {
                  delegate.configure(root);
                }

                @Override
                public Class<? extends org.apache.calcite.rel.type.RelDataTypeSystem> typeSystem() {
                  return delegate.typeSystem();
                }

                @Override
                public java.time.ZoneId timeZone() {
                  return delegate.timeZone();
                }

                @Override
                public CalciteWriteAdmission writeAdmission() {
                  return plan -> {
                    if (delegate.writeAdmission() != null) {
                      delegate.writeAdmission().verify(plan);
                    }
                    admissions.incrementAndGet();
                    assertEquals(CalciteWritePlan.Operation.INSERT, plan.operation());
                    for (var use : plan.tables()) {
                      assertEquals(table, use.table());
                      try (var borrowed = use.dataSource().getConnection()) {
                        assertSame(owner, assembly.writeConnection(use.dataSource()));
                        assertEquals(ownedSession, sessionId(borrowed, postgres));
                        assertFalse(borrowed.getAutoCommit());
                      } catch (SQLException failure) {
                        throw new IllegalStateException(
                            "Native session verification failed", failure);
                      }
                    }
                    if (denied.get()) {
                      throw refusal;
                    }
                  };
                }
              };
          try {
            assertFalse(lock(observer, postgres, lockId, true));
            var result =
                new DefaultCalciteQueryEngine()
                    .executeUpdate(
                        new CalciteUpdateRequest(
                            "INSERT INTO ds." + table + " (id) VALUES (?)",
                            "ds",
                            10_000,
                            List.of(1)),
                        schemas);
            assertEquals(1, result.affectedRows());
            assertEquals(
                1,
                new DefaultCalciteQueryEngine()
                    .executeUpdate(
                        new CalciteUpdateRequest(
                            "INSERT INTO ds."
                                + table
                                + " (id) SELECT id + 1 FROM ds."
                                + table
                                + " WHERE id = 1",
                            "ds",
                            10_000,
                            null),
                        schemas)
                    .affectedRows());
            denied.set(true);
            assertSame(
                refusal,
                assertThrows(
                    RuntimeException.class,
                    () ->
                        new DefaultCalciteQueryEngine()
                            .executeUpdate(
                                new CalciteUpdateRequest(
                                    "INSERT INTO ds." + table + " (id) VALUES (99)",
                                    "ds",
                                    10_000,
                                    null),
                                schemas)));
            assertEquals(3, admissions.get());
            try (var check = owner.createStatement();
                var rows = check.executeQuery("SELECT COUNT(*) FROM " + table)) {
              assertTrue(rows.next());
              assertEquals(2, rows.getInt(1));
            }
            assertEquals(1, acquired.get());
            assertEquals(0, released.get());
            assertFalse(lock(observer, postgres, lockId, true));
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
              assertTrue(rows.next());
              assertEquals(0, rows.getInt(1));
            }
            owner.rollback();
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
              assertTrue(rows.next());
              assertEquals(0, rows.getInt(1));
            }
          } finally {
            owner.rollback();
            assertTrue(lock(owner, postgres, lockId, false));
          }
        }
        assertEquals(1, released.get());
        assertTrue(lock(observer, postgres, lockId, true));
        assertTrue(lock(observer, postgres, lockId, false));
      } finally {
        statement.execute("DROP TABLE IF EXISTS " + table);
      }
    }
  }

  private static long sessionId(Connection connection, boolean postgres) throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                postgres ? "SELECT pg_catalog.pg_backend_pid()" : "SELECT CONNECTION_ID()")) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static boolean lock(Connection connection, boolean postgres, long id, boolean acquire)
      throws SQLException {
    String sql =
        postgres
            ? (acquire ? "SELECT pg_try_advisory_lock(?)" : "SELECT pg_advisory_unlock(?)")
            : (acquire ? "SELECT GET_LOCK(?,0)" : "SELECT RELEASE_LOCK(?)");
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      if (postgres) {
        statement.setLong(1, id);
      } else {
        statement.setString(1, "sp-write-test-" + id);
      }
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getBoolean(1) && !rows.wasNull();
      }
    }
  }
}
