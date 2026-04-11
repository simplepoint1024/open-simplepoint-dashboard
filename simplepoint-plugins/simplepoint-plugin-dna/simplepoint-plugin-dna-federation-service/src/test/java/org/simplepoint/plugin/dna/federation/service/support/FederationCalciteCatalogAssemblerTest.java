package org.simplepoint.plugin.dna.federation.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
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

@ExtendWith(MockitoExtension.class)
class FederationCalciteCatalogAssemblerTest {

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private JdbcDriverDefinitionService driverService;

  @Mock
  private JdbcDialectManagementService dialectManagementService;

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
    definition.setCode("ds1");
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

    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(dataSource));
    when(driverService.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationCalciteCatalogAssembler assembler = new FederationCalciteCatalogAssembler(
        dataSourceService,
        driverService,
        dialectManagementService
    );

    CalciteQueryEngine engine = new DefaultCalciteQueryEngine();
    try (FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = assembler.assemble("ds1", List.of(definition))) {
      CalciteQueryResult result = engine.execute(
          new CalciteQueryRequest(
              """
                  select r.id, t.name
                  from ds1.root_table r
                  join ds1.tenant_a.tenants t on r.tenant_id = t.id
                  order by r.id
                  """,
              "ds1",
              100,
              5_000
          ),
          assembly.schemaConfigurer()
      );

      assertEquals(List.of("ds1"), assembly.physicalDataSourceCodes());
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
    definition.setCode("ds1");
    definition.setDriverId("driver-1");

    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.h2.Driver");
    JdbcDatabaseDialect dialect = postgresLikeDialect();

    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(new SimpleDataSource(
        wrapDataSource(mainDataSource, "main_db", List.of("main_db", "audit_db"))
    ));
    when(dataSourceService.createTransientSimpleDataSource("ds-1", "jdbc:catalog:audit_db")).thenReturn(
        new SimpleDataSource(auditDataSource)
    );
    when(driverService.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationCalciteCatalogAssembler assembler = new FederationCalciteCatalogAssembler(
        dataSourceService,
        driverService,
        dialectManagementService
    );

    CalciteQueryEngine engine = new DefaultCalciteQueryEngine();
    try (FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = assembler.assemble("ds1", List.of(definition))) {
      CalciteQueryResult result = engine.execute(
          new CalciteQueryRequest(
              """
                  select t.id, t.name
                  from ds1.audit_db.tenant_a.tenants t
                  order by t.id
                  """,
              "ds1",
              100,
              5_000
          ),
          assembly.schemaConfigurer()
      );

      assertEquals(List.of("ds1"), assembly.physicalDataSourceCodes());
      assertEquals(1, result.returnedRows());
      assertEquals("Alice", result.rows().get(0).get(1));
    }

    try (FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = assembler.assemble("ds1", List.of(definition))) {
      SchemaPlus rootSchema = Frameworks.createRootSchema(true);
      assembly.schemaConfigurer().configure(rootSchema);
      SchemaPlus dataSourceSchema = rootSchema.getSubSchema("ds1");
      assertNotNull(dataSourceSchema);
      assertNotNull(dataSourceSchema.getSubSchema("main_db"));
      assertNotNull(dataSourceSchema.getSubSchema("audit_db"));
    }
  }

  @Test
  void sanitizeCalciteConventionNameShouldHandleChineseCharacters() {
    assertEquals("S_u6D4B_u8BD5", FederationCalciteCatalogAssembler.sanitizeCalciteConventionName("测试"));
    assertEquals("abc", FederationCalciteCatalogAssembler.sanitizeCalciteConventionName("abc"));
    assertEquals("abc_u002D123", FederationCalciteCatalogAssembler.sanitizeCalciteConventionName("abc-123"));
    assertEquals(null, FederationCalciteCatalogAssembler.sanitizeCalciteConventionName(null));
    assertEquals("my_u0020schema", FederationCalciteCatalogAssembler.sanitizeCalciteConventionName("my schema"));
  }

  @Test
  void assembleShouldHandleChineseSchemaNames() throws Exception {
    JdbcDataSource dataSource = createDataSource();
    initialize(dataSource, """
        create schema if not exists "\u6D4B\u8BD5";
        create table "\u6D4B\u8BD5".items (
          id int primary key,
          name varchar(64) not null
        );
        insert into "\u6D4B\u8BD5".items(id, name) values (1, 'hello');
        """);

    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-cn");
    definition.setCode("ds_cn");
    definition.setDriverId("driver-cn");

    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-cn");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.h2.Driver");
    JdbcDatabaseDialect dialect = mock(JdbcDatabaseDialect.class, Answers.CALLS_REAL_METHODS);
    when(dialect.metadataCatalog(any(), any())).thenReturn(null);
    when(dialect.visibleSchemas(any(), any(), any())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0)
        .stream()
        .filter(schema -> schema != null && !"INFORMATION_SCHEMA".equalsIgnoreCase(schema))
        .toList());

    when(dataSourceService.requireSimpleDataSource("ds-cn")).thenReturn(new SimpleDataSource(dataSource));
    when(driverService.findActiveById("driver-cn")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    FederationCalciteCatalogAssembler assembler = new FederationCalciteCatalogAssembler(
        dataSourceService,
        driverService,
        dialectManagementService
    );

    CalciteQueryEngine engine = new DefaultCalciteQueryEngine();
    try (FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = assembler.assemble("ds_cn", List.of(definition))) {
      // Chinese schema name should NOT crash Calcite convention/rule validation
      CalciteQueryResult result = engine.execute(
          new CalciteQueryRequest(
              "select id, name from ds_cn.\"\u6D4B\u8BD5\".items order by id",
              "ds_cn",
              100,
              5_000
          ),
          assembly.schemaConfigurer()
      );

      assertEquals(1, result.returnedRows());
      assertEquals("hello", result.rows().get(0).get(1));
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
    return new DataSource() {
      @Override
      public Connection getConnection() throws java.sql.SQLException {
        return wrapConnection(delegate.getConnection(), currentCatalog, visibleCatalogs, null);
      }

      @Override
      public Connection getConnection(final String username, final String password) throws java.sql.SQLException {
        return wrapConnection(delegate.getConnection(username, password), currentCatalog, visibleCatalogs, null);
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
          if ("getSchema".equals(method.getName())) {
            return currentSchema;
          }
          if ("getMetaData".equals(method.getName())) {
            return wrapMetaData(delegate.getMetaData(), currentCatalog, visibleCatalogs, currentSchema);
          }
          return method.invoke(delegate, args);
        }
    );
  }

  private static DatabaseMetaData wrapMetaData(
      final DatabaseMetaData delegate,
      final String currentCatalog,
      final List<String> visibleCatalogs,
      final String currentSchema
  ) {
    return (DatabaseMetaData) Proxy.newProxyInstance(
        DatabaseMetaData.class.getClassLoader(),
        new Class<?>[]{DatabaseMetaData.class},
        (proxy, method, args) -> {
          if ("getCatalogs".equals(method.getName())) {
            org.h2.tools.SimpleResultSet resultSet = new org.h2.tools.SimpleResultSet();
            resultSet.addColumn("TABLE_CAT", java.sql.Types.VARCHAR, 255, 0);
            if (visibleCatalogs != null) {
              for (String visibleCatalog : visibleCatalogs) {
                resultSet.addRow(visibleCatalog);
              }
            }
            return resultSet;
          }
          if ("getConnection".equals(method.getName())) {
            return wrapConnection(delegate.getConnection(), currentCatalog, visibleCatalogs, currentSchema);
          }
          return method.invoke(delegate, args);
        }
    );
  }
}
