package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.data.ownership.PhysicalObjectOwner;
import org.simplepoint.data.ownership.PhysicalOwnershipInstaller;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.MysqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.service.dialect.PostgresqlJdbcDatabaseDialect;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels.SqlConsoleRequest;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler;
import org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared isolated native database and real console for ownership integration tests. */
final class FederationSqlOwnershipFixture implements AutoCloseable {
  final boolean postgres;
  final String name = "sql_owner_" + UUID.randomUUID().toString().replace("-", "");
  final DriverManagerDataSource base;
  final SimpleDataSource pool;
  final FederationSqlConsoleServiceImpl console;
  volatile Connection lastBorrowed;
  boolean dirtyBorrow;
  String sessionSql;

  FederationSqlOwnershipFixture(boolean postgres, boolean install) throws Exception {
    this.postgres = postgres;
    String prefix = "DNA_FEDERATION_TEST_" + (postgres ? "POSTGRES" : "MYSQL");
    String url = System.getenv(prefix + "_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "Disposable real database is required");
    base =
        new DriverManagerDataSource(
            url, System.getenv(prefix + "_USER"), System.getenv(prefix + "_PASSWORD"));
    try (var connection = base.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("CREATE " + (postgres ? "SCHEMA " : "DATABASE ") + name);
    }
    pool =
        new SimpleDataSource(
            new AbstractDataSource() {
              @Override
              public Connection getConnection() throws SQLException {
                var connection = base.getConnection();
                try {
                  if (postgres) {
                    connection.setSchema(name);
                  } else {
                    connection.setCatalog(name);
                  }
                  if (sessionSql != null) {
                    try (var statement = connection.createStatement()) {
                      statement.execute(sessionSql);
                    }
                  }
                  if (dirtyBorrow) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                      statement.executeUpdate("INSERT INTO ordinary VALUES (999, 999)");
                    }
                  }
                  lastBorrowed = connection;
                  return connection;
                } catch (SQLException failure) {
                  connection.close();
                  throw failure;
                }
              }

              @Override
              public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLException("Unexpected credential override");
              }
            });
    if (install) {
      try (var connection = pool.getConnection()) {
        UUID identity = UUID.randomUUID();
        PhysicalOwnershipInstaller.install(connection, name, identity);
        PhysicalOwnershipInstaller.activateFresh(connection, name, identity);
      }
    }
    console = console("ds");
  }

  FederationSqlConsoleServiceImpl console(String code) {
    return console(code, pool);
  }

  FederationSqlConsoleServiceImpl console(String code, SimpleDataSource executionSource) {
    final var sources = mock(JdbcDataSourceDefinitionService.class);
    final var drivers = mock(JdbcDriverDefinitionService.class);
    final var dialects = mock(JdbcDialectManagementService.class);
    var definition = new JdbcDataSourceDefinition();
    definition.setId("physical-" + code);
    definition.setCode(code);
    definition.setDriverId("driver");
    definition.setEnabled(true);
    definition.setJdbcUrl(base.getUrl());
    var driver = new JdbcDriverDefinition();
    driver.setDatabaseType(postgres ? "postgresql" : "mysql");
    driver.setDriverClassName(postgres ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver");
    JdbcDatabaseDialect dialect =
        postgres ? new PostgresqlJdbcDatabaseDialect() : new MysqlJdbcDatabaseDialect();
    when(sources.findActiveByCode(code)).thenReturn(Optional.of(definition));
    when(sources.listEnabledDefinitions()).thenReturn(List.of(definition));
    when(sources.requireSimpleDataSource(definition.getId())).thenReturn(executionSource);
    when(drivers.findActiveById("driver")).thenReturn(Optional.of(driver));
    when(dialects.resolveDialect(any())).thenReturn(Optional.of(dialect));
    return new FederationSqlConsoleServiceImpl(
        sources,
        mock(FederationQueryPolicyRepository.class),
        mock(FederationQueryAuditService.class),
        new FederationCalciteCatalogAssembler(
            sources, drivers, dialects, new FederationMetadataCacheService()),
        new DefaultCalciteQueryEngine(),
        new FederationMetadataCacheService(),
        new FederationSqlWriteOwnership());
  }

  void create(String table, boolean managed) throws Exception {
    if (managed) {
      try (var connection = pool.getConnection();
          var session = PhysicalOwnershipSession.open(connection, name)) {
        session.claimNew(
            table, true, new PhysicalObjectOwner("dna-runtime", "other-tenant", table));
      }
    }
    execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, amount INTEGER)");
  }

  long write(String sql, List<Object> parameters) {
    return console.executeUpdate(new SqlConsoleRequest("ds", sql, null, parameters)).affectedRows();
  }

  void execute(String sql) throws SQLException {
    try (var connection = pool.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  int scalar(String sql) throws SQLException {
    try (var connection = pool.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getInt(1);
    }
  }

  void assertUnlocked() throws Exception {
    try (var connection = pool.getConnection();
        var session = PhysicalOwnershipSession.open(connection, name)) {
      session.verifyExecutionSession();
    }
  }

  boolean lockPresent(Connection observer) throws Exception {
    // Observe the documented namespace lock without briefly acquiring it and racing the writer.
    String key =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        ("simplepoint-physical-namespace-v1:" + name.length() + ":" + name)
                            .getBytes(StandardCharsets.UTF_8)));
    String sql =
        postgres
            ? "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks WHERE locktype='advisory'"
                + " AND granted AND objsubid=1 AND classid=CAST(? AS pg_catalog.oid)"
                + " AND objid=CAST(? AS pg_catalog.oid) AND database=(SELECT oid FROM"
                + " pg_catalog.pg_database WHERE datname=pg_catalog.current_database()))"
            : "SELECT IS_USED_LOCK(?) IS NOT NULL";
    try (var statement = observer.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      if (postgres) {
        long value = Long.parseUnsignedLong(key.substring(0, 16), 16);
        statement.setLong(1, value >>> 32);
        statement.setLong(2, value & 0xffffffffL);
      } else {
        statement.setString(1, key);
      }
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getBoolean(1);
      }
    }
  }

  @Override
  public void close() throws SQLException {
    pool.close();
    try (var connection = base.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP " + (postgres ? "SCHEMA " : "DATABASE ") + name + (postgres ? " CASCADE" : ""));
    }
  }
}
