package org.simplepoint.plugin.dna.federation.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.SimpleResultSet;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.tools.Frameworks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.repository.FederationSchemaRepository;
import org.simplepoint.plugin.dna.federation.api.repository.FederationViewRepository;

@ExtendWith(MockitoExtension.class)
class FederationCalciteCatalogAssemblerTest {

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private JdbcDriverDefinitionService driverService;

  @Mock
  private JdbcDialectManagementService dialectManagementService;

  @Mock
  private FederationSchemaRepository schemaRepository;

  @Mock
  private FederationViewRepository viewRepository;

  @Test
  void assembleShouldExposeNestedPhysicalSchemas() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource, """
        create schema if not exists tenant_a;
        create table root_table (
          id int primary key,
          tenant_id int not null
        );
        create table tenant_a.tenants (
          id int primary key,
          name varchar(64) not null
        );
        insert into root_table(id, tenant_id) values (1, 100);
        insert into tenant_a.tenants(id, name) values (100, 'Alice');
        """);

    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-1");
    definition.setCode("PG");
    definition.setDriverId("driver-1");

    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.h2.Driver");
    JdbcDatabaseDialect dialect = mock(JdbcDatabaseDialect.class, Answers.CALLS_REAL_METHODS);
    when(dialect.metadataCatalog(any(), any())).thenReturn(null);
    when(dialect.visibleSchemas(any(), any(), any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0)
        .stream()
        .filter(schema -> schema != null && !"INFORMATION_SCHEMA".equalsIgnoreCase(schema))
        .toList());

    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(definition));
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverService.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));
    when(schemaRepository.findAllActiveByCatalogId("catalog-1")).thenReturn(List.of());

    FederationCalciteCatalogAssembler assembler = new FederationCalciteCatalogAssembler(
        dataSourceService,
        driverService,
        dialectManagementService,
        schemaRepository,
        viewRepository
    );
    FederationCatalog catalog = new FederationCatalog();
    catalog.setId("catalog-1");
    catalog.setCode("demo");

    CalciteQueryEngine engine = new DefaultCalciteQueryEngine();
    try (FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = assembler.assemble(catalog)) {
      CalciteQueryResult result = engine.execute(
          new CalciteQueryRequest(
              """
                  select r.id, t.name
                  from PG.root_table r
                  join PG.tenant_a.tenants t on r.tenant_id = t.id
                  order by r.id
                  """,
              "demo",
              100,
              5_000
          ),
          assembly.schemaConfigurer()
      );

      assertEquals(List.of("PG"), assembly.physicalDataSourceCodes());
      assertEquals(1, result.returnedRows());
      assertEquals("Alice", result.rows().get(0).get(1));
    }
  }

  @Test
  void assembleShouldExposeCatalogAndSchemaNamespaces() throws Exception {
    JdbcDataSource mainDataSource = createDataSource();
    JdbcDataSource auditDataSource = createDataSource();
    initialize(mainDataSource, """
        create table root_table (
          id int primary key,
          tenant_id int not null
        );
        insert into root_table(id, tenant_id) values (1, 100);
        """);
    initialize(auditDataSource, """
        create schema if not exists tenant_a;
        create table tenant_a.tenants (
          id int primary key,
          name varchar(64) not null
        );
        insert into tenant_a.tenants(id, name) values (100, 'Alice');
        """);

    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-1");
    definition.setCode("PG");
    definition.setDriverId("driver-1");

    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.h2.Driver");
    JdbcDatabaseDialect dialect = postgresLikeDialect();

    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(definition));
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(
        wrapDataSource(mainDataSource, "main_db", List.of("main_db", "audit_db"))
    ));
    when(dataSourceService.createTransientSimpleDataSource("ds-1", "jdbc:catalog:audit_db")).thenReturn(
        new SimpleDataSource(auditDataSource)
    );
    when(driverService.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));
    when(schemaRepository.findAllActiveByCatalogId("catalog-1")).thenReturn(List.of());

    FederationCalciteCatalogAssembler assembler = new FederationCalciteCatalogAssembler(
        dataSourceService,
        driverService,
        dialectManagementService,
        schemaRepository,
        viewRepository
    );
    FederationCatalog catalog = new FederationCatalog();
    catalog.setId("catalog-1");
    catalog.setCode("demo");

    CalciteQueryEngine engine = new DefaultCalciteQueryEngine();
    try (FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = assembler.assemble(catalog)) {
      CalciteQueryResult result = engine.execute(
          new CalciteQueryRequest(
              """
                  select t.id, t.name
                  from PG.audit_db.tenant_a.tenants t
                  order by t.id
                  """,
              "demo",
              100,
              5_000
          ),
          assembly.schemaConfigurer()
      );

      assertEquals(List.of("PG"), assembly.physicalDataSourceCodes());
      assertEquals(1, result.returnedRows());
      assertEquals("Alice", result.rows().get(0).get(1));
    }
  }

  @Test
  void assembleShouldExposeCurrentCatalogAsAliasNamespace() throws Exception {
    JdbcDataSource mainDataSource = createDataSource();
    initialize(mainDataSource, """
        create schema if not exists tenant_a;
        create table tenant_a.root_table (
          id int primary key,
          tenant_id int not null
        );
        insert into tenant_a.root_table(id, tenant_id) values (1, 100);
        """);

    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-1");
    definition.setCode("PG");
    definition.setDriverId("driver-1");

    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.h2.Driver");
    JdbcDatabaseDialect dialect = mock(JdbcDatabaseDialect.class, Answers.CALLS_REAL_METHODS);
    when(dialect.metadataCatalog(any(), any())).thenReturn(null);
    when(dialect.visibleCatalogs(any(), any())).thenReturn(List.of());
    when(dialect.visibleSchemas(any(), any(), any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0)
        .stream()
        .filter(schema -> schema != null && !"INFORMATION_SCHEMA".equalsIgnoreCase(schema))
        .toList());

    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(definition));
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(
        wrapDataSource(mainDataSource, "main_db", null, "TENANT_A")
    ));
    when(driverService.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));
    when(schemaRepository.findAllActiveByCatalogId("catalog-1")).thenReturn(List.of());

    FederationCalciteCatalogAssembler assembler = new FederationCalciteCatalogAssembler(
        dataSourceService,
        driverService,
        dialectManagementService,
        schemaRepository,
        viewRepository
    );
    FederationCatalog catalog = new FederationCatalog();
    catalog.setId("catalog-1");
    catalog.setCode("demo");

    try (FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = assembler.assemble(catalog)) {
      SchemaPlus rootSchema = Frameworks.createRootSchema(true);
      assembly.schemaConfigurer().configure(rootSchema);
      SchemaPlus demoSchema = rootSchema.getSubSchema("demo");
      assertNotNull(demoSchema);
      SchemaPlus dataSourceSchema = demoSchema.getSubSchema("PG");
      assertNotNull(dataSourceSchema);
      SchemaPlus currentCatalogSchema = dataSourceSchema.getSubSchema("main_db");
      assertNotNull(currentCatalogSchema);
      assertNotNull(currentCatalogSchema.getSubSchema("TENANT_A"));
      assertEquals(List.of("PG"), assembly.physicalDataSourceCodes());
    }
  }

  private static JdbcDataSource createDataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:federation-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private static void initialize(final JdbcDataSource dataSource, final String sql) throws Exception {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {
      for (String fragment : sql.split(";")) {
        String normalized = fragment.trim();
        if (!normalized.isEmpty()) {
          statement.execute(normalized);
        }
      }
    }
  }

  private static JdbcDatabaseDialect postgresLikeDialect() {
    JdbcDatabaseDialect dialect = mock(JdbcDatabaseDialect.class, Answers.CALLS_REAL_METHODS);
    when(dialect.metadataCatalog(any(), any())).thenReturn(null);
    when(dialect.visibleCatalogs(any(), any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0));
    when(dialect.visibleSchemas(any(), any(), any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0)
        .stream()
        .filter(schema -> schema != null && !"INFORMATION_SCHEMA".equalsIgnoreCase(schema))
        .toList());
    when(dialect.requiresCatalogConnection(any(), any())).thenAnswer(invocation -> {
      String targetCatalog = invocation.getArgument(0);
      JdbcDatabaseDialect.SupportContext context = invocation.getArgument(1);
      return targetCatalog != null && !targetCatalog.equals(context.currentCatalog());
    });
    when(dialect.remapJdbcUrlCatalog(any(), any(), any())).thenAnswer(invocation ->
        "jdbc:catalog:" + invocation.getArgument(1, String.class));
    return dialect;
  }

  private static DataSource wrapDataSource(
      final DataSource delegate,
      final String currentCatalog,
      final List<String> visibleCatalogs
  ) {
    return wrapDataSource(delegate, currentCatalog, visibleCatalogs, null);
  }

  private static DataSource wrapDataSource(
      final DataSource delegate,
      final String currentCatalog,
      final List<String> visibleCatalogs,
      final String currentSchema
  ) {
    return new DataSource() {
      @Override
      public Connection getConnection() throws java.sql.SQLException {
        return wrapConnection(delegate.getConnection(), currentCatalog, visibleCatalogs, currentSchema);
      }

      @Override
      public Connection getConnection(final String username, final String password) throws java.sql.SQLException {
        return wrapConnection(delegate.getConnection(username, password), currentCatalog, visibleCatalogs, currentSchema);
      }

      @Override
      public PrintWriter getLogWriter() throws java.sql.SQLException {
        return delegate.getLogWriter();
      }

      @Override
      public void setLogWriter(final PrintWriter out) throws java.sql.SQLException {
        delegate.setLogWriter(out);
      }

      @Override
      public void setLoginTimeout(final int seconds) throws java.sql.SQLException {
        delegate.setLoginTimeout(seconds);
      }

      @Override
      public int getLoginTimeout() throws java.sql.SQLException {
        return delegate.getLoginTimeout();
      }

      @Override
      public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
      }

      @Override
      public <T> T unwrap(final Class<T> iface) throws java.sql.SQLException {
        return delegate.unwrap(iface);
      }

      @Override
      public boolean isWrapperFor(final Class<?> iface) throws java.sql.SQLException {
        return delegate.isWrapperFor(iface);
      }
    };
  }

  private static Connection wrapConnection(
      final Connection delegate,
      final String currentCatalog,
      final List<String> visibleCatalogs
  ) {
    return wrapConnection(delegate, currentCatalog, visibleCatalogs, null);
  }

  private static Connection wrapConnection(
      final Connection delegate,
      final String currentCatalog,
      final List<String> visibleCatalogs,
      final String currentSchema
  ) {
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[]{Connection.class},
        (proxy, method, args) -> {
          if ("getCatalog".equals(method.getName())) {
            return currentCatalog;
          }
          if ("getSchema".equals(method.getName()) && currentSchema != null) {
            return currentSchema;
          }
          if ("getMetaData".equals(method.getName()) && visibleCatalogs != null) {
            return wrapMetaData(delegate.getMetaData(), visibleCatalogs);
          }
          return invoke(delegate, method, args);
        }
    );
  }

  private static DatabaseMetaData wrapMetaData(
      final DatabaseMetaData delegate,
      final List<String> visibleCatalogs
  ) {
    return (DatabaseMetaData) Proxy.newProxyInstance(
        DatabaseMetaData.class.getClassLoader(),
        new Class<?>[]{DatabaseMetaData.class},
        (proxy, method, args) -> {
          if ("getCatalogs".equals(method.getName())) {
            return catalogResultSet(visibleCatalogs);
          }
          return invoke(delegate, method, args);
        }
    );
  }

  private static ResultSet catalogResultSet(final List<String> visibleCatalogs) {
    SimpleResultSet resultSet = new SimpleResultSet();
    resultSet.addColumn("TABLE_CAT", Types.VARCHAR, 255, 0);
    for (String visibleCatalog : visibleCatalogs) {
      resultSet.addRow(visibleCatalog);
    }
    return resultSet;
  }

  private static Object invoke(final Object target, final Method method, final Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException ex) {
      throw ex.getCause();
    }
  }
}
