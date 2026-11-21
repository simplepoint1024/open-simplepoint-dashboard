package org.simplepoint.plugin.dna.federation.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.springframework.jdbc.datasource.AbstractDataSource;

class FederationCalciteWriteAssemblyTest {
  @Test
  void ambiguousSourceDeclarationsAreRejectedBeforeAcquiringSessions() throws Exception {
    var fixture = new Fixture();
    var duplicate = new JdbcDataSourceDefinition();
    duplicate.setId("other");
    duplicate.setCode("ds");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.assembler.assembleForWrite(
                "ds", List.of(fixture.definition, duplicate), Map.of()));
    assertEquals(0, fixture.main.opened.get());
  }

  @Test
  void metadataAndDmlUseOneOwnedSessionWithoutQueryCacheEntry() throws Exception {
    var fixture = new Fixture();
    final var engine = new DefaultCalciteQueryEngine();
    var assembly = fixture.assembler.assembleForWrite("ds", List.of(fixture.definition), Map.of());
    assertFalse(assembly.schemaCacheHit());
    assertEquals(1, assembly.writeConnections().size());
    var owner = assembly.writeConnections().getFirst();
    owner.setAutoCommit(false);
    assertEquals(
        1,
        engine
            .executeUpdate(
                new CalciteUpdateRequest("INSERT INTO ds.orders VALUES (1)", "ds", 5000, null),
                assembly.schemaConfigurer())
            .affectedRows());
    assertEquals(1, fixture.main.opened.get());
    assertEquals(0, fixture.main.closed.get());
    assertEquals(0, fixture.main.count());
    owner.rollback();
    assertEquals(0, fixture.main.count());
    assertEquals(0, fixture.assembler.flushSchemaCache());
    assembly.close();
    assertTrue(owner.isClosed());
    assertEquals(1, fixture.main.closed.get());
    assertThrows(SQLException.class, assembly::writeConnections);
    try (var next =
        fixture.assembler.assembleForWrite("ds", List.of(fixture.definition), Map.of())) {
      assertThrows(
          RuntimeException.class,
          () ->
              engine.executeUpdate(
                  new CalciteUpdateRequest("INSERT INTO ds.orders VALUES (2)", "ds", 5000, null),
                  assembly.schemaConfigurer()));
      assertEquals(2, fixture.main.opened.get());
      assertFalse(next.writeConnections().getFirst().isClosed());
      assertEquals(
          1,
          engine
              .executeUpdate(
                  new CalciteUpdateRequest("INSERT INTO ds.orders VALUES (3)", "ds", 5000, null),
                  next.schemaConfigurer())
              .affectedRows());
    }
    assertEquals(2, fixture.main.closed.get());
    assertEquals(1, fixture.main.count());
  }

  @Test
  void failedMetadataAssemblyClosesItsAlreadyAcquiredSession() throws Exception {
    var fixture = new Fixture();
    org.mockito.Mockito.doThrow(new IllegalStateException("metadata unavailable"))
        .when(fixture.dialect)
        .visibleSchemas(any(), any(), any());
    assertThrows(
        IllegalStateException.class,
        () -> fixture.assembler.assembleForWrite("ds", List.of(fixture.definition), Map.of()));
    assertEquals(1, fixture.main.opened.get());
    assertEquals(1, fixture.main.closed.get());
    assertEquals(0, fixture.assembler.flushSchemaCache());
  }

  @Test
  void remappedCatalogKeepsItsOwnPinnedSessionUntilTheWriteEnds() throws Exception {
    var fixture = new Fixture();
    var audit = new CountingSource();
    when(fixture.dialect.visibleCatalogs(any(), any())).thenReturn(List.of("audit"));
    when(fixture.dialect.requiresCatalogConnection(any(), any()))
        .thenAnswer(call -> "audit".equals(call.getArgument(0)));
    when(fixture.dialect.remapJdbcUrlCatalog(any(), any(), any())).thenReturn("jdbc:scope:audit");
    var auditPool = new SimpleDataSource(audit);
    when(fixture.sources.createTransientSimpleDataSource("source", "jdbc:scope:audit"))
        .thenReturn(auditPool);
    try (var assembly =
        fixture.assembler.assembleForWrite(
            "ds", List.of(fixture.definition), Map.of("ds", Set.of("audit")))) {
      assertEquals(2, assembly.writeConnections().size());
      for (var connection : assembly.writeConnections()) {
        connection.setAutoCommit(false);
      }
      var result =
          new DefaultCalciteQueryEngine()
              .executeUpdate(
                  new CalciteUpdateRequest(
                      "INSERT INTO ds.audit.public.orders VALUES (1)", "ds", 5000, null),
                  assembly.schemaConfigurer());
      assertEquals(1, result.affectedRows());
      assertEquals(1, fixture.main.opened.get());
      assertEquals(1, audit.opened.get());
      assertEquals(0, audit.count());
      assembly.writeConnections().get(1).commit();
      assertEquals(1, audit.count());
      assertEquals(0, fixture.main.count());
    }
    assertEquals(1, fixture.main.closed.get());
    assertEquals(1, audit.closed.get());
    assertTrue(audit.poolClosed.get());
    assertFalse(fixture.main.poolClosed.get());
  }

  private static final class Fixture {
    private final CountingSource main = new CountingSource();
    private final JdbcDataSourceDefinitionService sources =
        mock(JdbcDataSourceDefinitionService.class);
    private final JdbcDatabaseDialect dialect =
        mock(JdbcDatabaseDialect.class, Answers.CALLS_REAL_METHODS);
    private final JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    private final FederationCalciteCatalogAssembler assembler;

    private Fixture() throws Exception {
      definition.setId("source");
      definition.setCode("ds");
      definition.setDriverId("driver");
      definition.setJdbcUrl("jdbc:scope:main");
      var driver = new JdbcDriverDefinition();
      driver.setDatabaseType("postgresql");
      driver.setDriverClassName("org.h2.Driver");
      var drivers = mock(JdbcDriverDefinitionService.class);
      var dialects = mock(JdbcDialectManagementService.class);
      when(sources.requireSimpleDataSource("source")).thenReturn(new SimpleDataSource(main));
      when(drivers.findActiveById("driver")).thenReturn(Optional.of(driver));
      when(dialects.resolveDialect(any())).thenReturn(Optional.of(dialect));
      when(dialect.metadataCatalog(any(), any())).thenReturn(null);
      when(dialect.visibleSchemas(any(), any(), any()))
          .thenAnswer(
              call ->
                  call.<List<String>>getArgument(0).stream()
                      .filter("PUBLIC"::equalsIgnoreCase)
                      .toList());
      assembler = new FederationCalciteCatalogAssembler(sources, drivers, dialects);
    }
  }

  private static final class CountingSource extends AbstractDataSource implements AutoCloseable {
    private final JdbcDataSource delegate = new JdbcDataSource();
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private final AtomicBoolean poolClosed = new AtomicBoolean();

    private CountingSource() throws SQLException {
      delegate.setURL("jdbc:h2:mem:write-scope-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      delegate.setUser("sa");
      try (var connection = delegate.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE orders (id INT PRIMARY KEY)");
      }
    }

    @Override
    public Connection getConnection() throws SQLException {
      opened.incrementAndGet();
      var connection = delegate.getConnection();
      var returned = new AtomicBoolean();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("close") && returned.compareAndSet(false, true)) {
                  closed.incrementAndGet();
                }
                try {
                  return method.invoke(connection, args);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      return getConnection();
    }

    private int count() throws SQLException {
      try (var connection = delegate.getConnection();
          var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }

    @Override
    public void close() {
      poolClosed.set(true);
    }
  }
}
