package org.simplepoint.plugin.dna.federation.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.calcite.core.jdbc.ReadOnlyJdbcDataSource;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
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

/**
 * Actual read transactions reject view/function writes even when JDBC read-only hints are ignored.
 */
class FederationReadSessionDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void nativeReadTransactionRejectsDirectAndViewWritesAndRestoresPooledConnection(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      var readOnly = new ReadOnlyJdbcDataSource(fixture.pool);
      long sessionId;
      try (var connection = readOnly.getConnection()) {
        sessionId = fixture.sessionId(connection);
        assertEquals(0, fixture.count(connection));
        var directFailure =
            assertThrows(
                SQLException.class,
                () ->
                    fixture.execute(
                        connection, "INSERT INTO " + fixture.table("records") + " VALUES (1)"));
        // Connector/J rejects obvious DML locally. The mutating-view assertion below proves the
        // database read-only transaction also rejects writes hidden inside a SELECT.
        assertEquals(postgres ? "25006" : "S1009", directFailure.getSQLState());
      }
      try (var connection = readOnly.getConnection()) {
        assertEquals(sessionId, fixture.sessionId(connection));
        var failure =
            assertThrows(
                SQLException.class,
                () ->
                    fixture.execute(connection, "SELECT * FROM " + fixture.table("mutating_view")));
        assertEquals("25006", failure.getSQLState());
      }
      try (var connection = fixture.pool.getConnection()) {
        assertEquals(sessionId, fixture.sessionId(connection));
        assertTrue(connection.getAutoCommit());
        assertFalse(connection.isReadOnly());
        assertEquals(0, fixture.count(connection));
        fixture.execute(connection, "INSERT INTO " + fixture.table("records") + " VALUES (10)");
        assertEquals(1, fixture.count(connection));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void cachedCalciteReadAssembliesEnforceReadOnlyAndDoNotPoisonWriteAssemblies(boolean postgres)
      throws Exception {
    try (var fixture = new Fixture(postgres)) {
      var catalog = catalog(fixture, null);
      var assembler = catalog.assembler();
      var definition = catalog.definition();
      var engine = new DefaultCalciteQueryEngine();
      try {
        for (int attempt = 0; attempt < 2; attempt++) {
          try (var assembly = assembler.assemble("ds", List.of(definition), Map.of())) {
            assertEquals(attempt > 0, assembly.schemaCacheHit());
            var result =
                engine.execute(
                    new CalciteQueryRequest("SELECT id FROM ds.records", "ds", 10, 5000),
                    assembly.schemaConfigurer());
            assertTrue(result.rows().isEmpty());
            var failure =
                assertThrows(
                    RuntimeException.class,
                    () ->
                        engine.execute(
                            new CalciteQueryRequest(
                                "SELECT id FROM ds.mutating_view", "ds", 10, 5000),
                            assembly.schemaConfigurer()));
            assertTrue(
                hasSqlState(failure, "25006"),
                "The database must reject the indirect write, not just Calcite parsing");
          }
        }
        try (var connection = fixture.pool.getConnection()) {
          assertEquals(0, fixture.count(connection));
        }
        try (var assembly = assembler.assembleForWrite("ds", List.of(definition), Map.of())) {
          assertEquals(
              1,
              engine
                  .executeUpdate(
                      new CalciteUpdateRequest(
                          "INSERT INTO ds.records (id) VALUES (11)", "ds", 5000, List.of()),
                      assembly.schemaConfigurer())
                  .affectedRows());
        }
        try (var connection = fixture.pool.getConnection()) {
          assertEquals(1, fixture.count(connection));
        }
      } finally {
        assembler.flushSchemaCache();
      }
      assertFalse(fixture.pool.isClosed());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void explicitRemoteCatalogsUseReadTransactionsAndRetainPoolOwnership(boolean postgres)
      throws Exception {
    try (var base = new Fixture(postgres)) {
      String remoteDatabase = "sp_read_catalog_" + UUID.randomUUID().toString().replace("-", "");
      if (postgres) {
        try (var connection = DriverManager.getConnection(base.url, base.user, base.password)) {
          base.maintenance(connection, "CREATE DATABASE " + base.quote(remoteDatabase));
        }
      }
      try {
        String remoteUrl =
            postgres
                ? new PostgresqlJdbcDatabaseDialect()
                    .remapJdbcUrlCatalog(base.url, remoteDatabase, null)
                : base.url;
        try (var remote = new Fixture(postgres, remoteUrl)) {
          String catalogName = postgres ? remoteDatabase : remote.namespace;
          var catalog = catalog(base, remote);
          String path = "ds." + catalogName + (postgres ? "." + remote.namespace : "");
          try {
            for (int attempt = 0; attempt < 2; attempt++) {
              try (var assembly =
                  catalog
                      .assembler()
                      .assemble(
                          "ds", List.of(catalog.definition()), Map.of("ds", Set.of(catalogName)))) {
                assertEquals(attempt > 0, assembly.schemaCacheHit());
                var engine = new DefaultCalciteQueryEngine();
                assertTrue(
                    engine
                        .execute(
                            new CalciteQueryRequest(
                                "SELECT id FROM " + path + ".records", "ds", 10, 5000),
                            assembly.schemaConfigurer())
                        .rows()
                        .isEmpty());
                var failure =
                    assertThrows(
                        RuntimeException.class,
                        () ->
                            engine.execute(
                                new CalciteQueryRequest(
                                    "SELECT id FROM " + path + ".mutating_view", "ds", 10, 5000),
                                assembly.schemaConfigurer()));
                assertTrue(
                    hasSqlState(failure, "25006"), "Remote native view write must be rejected");
              }
            }
            try (var connection = remote.pool.getConnection()) {
              assertEquals(0, remote.count(connection));
            }
          } finally {
            catalog.assembler().flushSchemaCache();
          }
          assertFalse(base.pool.isClosed());
          assertEquals(
              postgres,
              remote.pool.isClosed(),
              "Only PostgreSQL creates an owned transient catalog pool");
        }
      } finally {
        if (postgres) {
          try (var connection = DriverManager.getConnection(base.url, base.user, base.password)) {
            base.maintenance(connection, "DROP DATABASE " + base.quote(remoteDatabase));
          }
        }
      }
    }
  }

  private static Catalog catalog(Fixture fixture, Fixture remote) {
    final var sources = mock(JdbcDataSourceDefinitionService.class);
    final var drivers = mock(JdbcDriverDefinitionService.class);
    final var dialects = mock(JdbcDialectManagementService.class);
    var definition = new JdbcDataSourceDefinition();
    definition.setId("physical");
    definition.setCode("ds");
    definition.setDriverId("driver");
    definition.setJdbcUrl(fixture.url);
    var driver = new JdbcDriverDefinition();
    driver.setDatabaseType(fixture.postgres ? "postgresql" : "mysql");
    driver.setDriverClassName(
        fixture.postgres ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver");
    JdbcDatabaseDialect dialect =
        fixture.postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    when(sources.requireSimpleDataSource("physical"))
        .thenReturn(new SimpleDataSource(fixture.pool));
    when(drivers.findActiveById("driver")).thenReturn(Optional.of(driver));
    when(dialects.resolveDialect(any())).thenReturn(Optional.of(dialect));
    var assembler = new FederationCalciteCatalogAssembler(sources, drivers, dialects);
    if (remote != null && fixture.postgres) {
      when(sources.createTransientSimpleDataSource("physical", remote.url))
          .thenReturn(new SimpleDataSource(remote.pool));
    }
    return new Catalog(definition, assembler);
  }

  private record Catalog(
      JdbcDataSourceDefinition definition, FederationCalciteCatalogAssembler assembler) {}

  @Test
  void mysqlRestoresIndependentServerModeAndAnUnusedLeaseLeavesNoPendingOverride()
      throws Exception {
    try (var fixture = new Fixture(false)) {
      try (var connection = fixture.pool.getConnection()) {
        fixture.execute(connection, "SET SESSION TRANSACTION READ ONLY");
      }
      var source = new ReadOnlyJdbcDataSource(fixture.pool);
      try (var connection = source.getConnection()) {
        assertEquals(0, fixture.count(connection));
      }
      try (var connection = fixture.pool.getConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT @@SESSION.transaction_read_only")) {
        assertTrue(rows.next());
        assertTrue(rows.getBoolean(1));
        fixture.execute(connection, "SET SESSION TRANSACTION READ WRITE");
      }
      try (var ignored = source.getConnection()) {
        // No business statement begins an InnoDB transaction in this borrow.
      }
      try (var connection = fixture.pool.getConnection()) {
        fixture.execute(connection, "INSERT INTO " + fixture.table("records") + " VALUES (12)");
        assertEquals(1, fixture.count(connection));
      }
    }
  }

  private static boolean hasSqlState(Throwable failure, String state) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sql && state.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static final class Fixture implements AutoCloseable {
    private final boolean postgres;
    private final String namespace = "sp_reads_" + UUID.randomUUID().toString().replace("-", "");
    private final String url;
    private final String user;
    private final String password;
    private final HikariDataSource pool = new HikariDataSource();

    private Fixture(boolean postgres) throws SQLException {
      this(postgres, null);
    }

    private Fixture(boolean postgres, String overrideUrl) throws SQLException {
      this.postgres = postgres;
      String prefix = "DNA_FEDERATION_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
      url = overrideUrl == null ? System.getenv(prefix + "_URL") : overrideUrl;
      Assumptions.assumeTrue(
          url != null && !url.isBlank(), "Disposable native database is required");
      user = System.getenv(prefix + "_USER");
      password = System.getenv(prefix + "_PASSWORD");
      try (var connection = DriverManager.getConnection(url, user, password)) {
        maintenance(
            connection, "CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + quote(namespace));
        maintenance(connection, "CREATE TABLE " + table("records") + " (id INTEGER PRIMARY KEY)");
        maintenance(
            connection,
            postgres
                ? "CREATE FUNCTION "
                    + table("mutate")
                    + "() RETURNS INTEGER LANGUAGE plpgsql AS $$ BEGIN INSERT INTO "
                    + table("records")
                    + " VALUES (2); RETURN 2; END; $$"
                : "CREATE FUNCTION "
                    + table("mutate")
                    + "() RETURNS INTEGER DETERMINISTIC MODIFIES SQL DATA BEGIN INSERT INTO "
                    + table("records")
                    + " VALUES (2); RETURN 2; END");
        maintenance(
            connection,
            "CREATE VIEW " + table("mutating_view") + " AS SELECT " + table("mutate") + "() AS id");
      } catch (SQLException | RuntimeException failure) {
        try {
          close();
        } catch (SQLException | RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
      pool.setJdbcUrl(url);
      pool.setUsername(user);
      pool.setPassword(password);
      pool.setMaximumPoolSize(1);
      pool.setMinimumIdle(0);
      pool.setConnectionTimeout(5000);
      if (postgres) {
        pool.setSchema(namespace);
        pool.addDataSourceProperty("readOnlyMode", "ignore");
      } else {
        pool.setCatalog(namespace);
        pool.addDataSourceProperty("readOnlyPropagatesToServer", "false");
      }
    }

    private String quote(String value) {
      String delimiter = postgres ? "\"" : "`";
      return delimiter + value + delimiter;
    }

    private String table(String table) {
      return quote(namespace) + "." + quote(table);
    }

    private void execute(Connection connection, String sql) throws SQLException {
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        statement.execute(sql);
      }
    }

    private void maintenance(Connection connection, String sql) throws SQLException {
      // Creating/dropping disposable databases can wait for catalog/checkpoint I/O. Keep this
      // budget separate from business statements and Calcite's explicit query deadlines.
      try (var statement = connection.createStatement()) {
        statement.setQueryTimeout(60);
        statement.execute(sql);
      }
    }

    private long sessionId(Connection connection) throws SQLException {
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  postgres ? "SELECT pg_catalog.pg_backend_pid()" : "SELECT CONNECTION_ID()")) {
        assertTrue(rows.next());
        return rows.getLong(1);
      }
    }

    private int count(Connection connection) throws SQLException {
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table("records"))) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }

    @Override
    public void close() throws SQLException {
      pool.close();
      try (var connection = DriverManager.getConnection(url, user, password)) {
        maintenance(
            connection,
            "DROP "
                + (postgres ? "SCHEMA " : "DATABASE ")
                + "IF EXISTS "
                + quote(namespace)
                + (postgres ? " CASCADE" : ""));
      }
    }
  }
}
