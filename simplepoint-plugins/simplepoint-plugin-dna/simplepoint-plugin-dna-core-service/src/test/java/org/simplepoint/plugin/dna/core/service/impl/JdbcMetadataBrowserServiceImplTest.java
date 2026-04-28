package org.simplepoint.plugin.dna.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;

@ExtendWith(MockitoExtension.class)
class JdbcMetadataBrowserServiceImplTest {

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private JdbcDriverDefinitionRepository driverRepository;

  @Mock
  private JdbcDialectManagementService dialectManagementService;

  @Mock
  private Connection connection;

  @Mock
  private DatabaseMetaData metaData;

  @Mock
  private JdbcDatabaseDialect dialect;

  @Test
  void children_withUnknownDataSourceId_throwsIllegalArgumentException() {
    when(dataSourceService.findActiveById("unknown-ds")).thenReturn(Optional.empty());
    JdbcMetadataBrowserServiceImpl service = new JdbcMetadataBrowserServiceImpl(
        dataSourceService, driverRepository, dialectManagementService
    );

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.children("unknown-ds", null)
    );
    assertTrue(ex.getMessage().contains("数据源不存在"));
  }

  @Test
  void structure_withNullRequest_throwsIllegalArgumentException() throws Exception {
    JdbcMetadataBrowserServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.structure("ds-1", null)
    );
    assertTrue(ex.getMessage().contains("当前路径必须指向数据表或视图"));
  }

  @Test
  void preview_withNullRequest_throwsIllegalArgumentException() throws Exception {
    JdbcMetadataBrowserServiceImpl service = createService();
    when(dialect.supportsDataPreview()).thenReturn(true);

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.preview("ds-1", null, null)
    );
    assertTrue(ex.getMessage().contains("当前路径必须指向数据表或视图"));
  }

  @Test
  void children_withRootPath_returnsEmptyWhenNoCatalogsOrSchemas() throws Exception {
    JdbcMetadataBrowserServiceImpl service = createService();

    when(dialect.loadCatalogs(any(), any(), any(), any())).thenReturn(
        new JdbcDatabaseDialect.MetadataResult(List.of(), List.of())
    );
    when(dialect.loadSchemas(any(), any(), any(), any(), any())).thenReturn(
        new JdbcDatabaseDialect.MetadataResult(List.of(), List.of())
    );
    when(dialect.loadTables(any(), any(), any(), any(), any(), any(), any())).thenReturn(
        new JdbcDatabaseDialect.MetadataResult(List.of(), List.of())
    );

    List<JdbcMetadataModels.TreeNode> result = service.children(
        "ds-1", new JdbcMetadataRequests.PathRequest(List.of())
    );

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void structure_withValidTablePath_returnsStructure() throws Exception {
    JdbcMetadataBrowserServiceImpl service = createService();

    when(dialect.loadColumns(any(), any(), any(), any(), any(), any(), any())).thenReturn(
        new JdbcDatabaseDialect.MetadataResult(List.of(), List.of())
    );
    when(dialect.loadConstraints(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dialect.code()).thenReturn("postgresql");
    when(dialect.name()).thenReturn("PostgreSQL");

    JdbcMetadataModels.TableStructure result = service.structure(
        "ds-1",
        new JdbcMetadataRequests.PathRequest(List.of(
            new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.DATABASE, "app_suite"),
            new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.SCHEMA, "public"),
            new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, "orders")
        ))
    );

    assertNotNull(result);
    assertEquals("postgresql", result.dialectCode());
    assertEquals("PostgreSQL", result.dialectName());

    verify(dialect).loadColumns(
        eq(connection),
        eq(metaData),
        any(),
        eq("app_suite"),
        eq("public"),
        eq("orders"),
        eq("%")
    );
  }

  private JdbcMetadataBrowserServiceImpl createService() throws Exception {
    JdbcDataSourceDefinition definition = new JdbcDataSourceDefinition();
    definition.setId("ds-1");
    definition.setDriverId("driver-1");
    definition.setConnectionProperties(null);

    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setId("driver-1");
    driver.setDatabaseType("postgresql");
    driver.setDriverClassName("org.postgresql.Driver");

    SimpleDataSource simpleDataSource = mock(SimpleDataSource.class);

    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(definition));
    when(dataSourceService.requireSimpleDataSource("ds-1")).thenReturn(simpleDataSource);
    when(simpleDataSource.getConnection()).thenReturn(connection);
    when(driverRepository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
    when(dialectManagementService.resolveDialect(any())).thenReturn(Optional.of(dialect));

    when(connection.getMetaData()).thenReturn(metaData);
    when(connection.getCatalog()).thenReturn("app_suite");
    when(connection.getSchema()).thenReturn("public");
    when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(metaData.getDatabaseProductVersion()).thenReturn("16");
    when(metaData.getIdentifierQuoteString()).thenReturn("\"");
    lenient().when(metaData.getSearchStringEscape()).thenReturn("\\");

    return new JdbcMetadataBrowserServiceImpl(dataSourceService, driverRepository, dialectManagementService);
  }
}
