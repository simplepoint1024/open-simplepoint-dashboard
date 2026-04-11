package org.simplepoint.plugin.dna.core.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;
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
class JdbcMetadataManagementServiceImplTest {

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
  void childrenShouldEscapeExactSchemaPatternWhenListingTables() throws Exception {
    JdbcMetadataManagementServiceImpl service = createService();

    when(dialect.loadTables(any(), any(), any(), any(), any(), any(), any())).thenReturn(
        new JdbcDatabaseDialect.MetadataResult(List.of(), List.of())
    );

    service.children("ds-1", new JdbcMetadataRequests.PathRequest(List.of(
        new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.DATABASE, "app_suite"),
        new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.SCHEMA, "tenant_%")
    )));

    verify(dialect).loadTables(
        eq(connection),
        eq(metaData),
        any(),
        eq("app_suite"),
        eq("tenant\\_\\%"),
        eq("%"),
        eq(List.of("TABLE", "VIEW"))
    );
  }

  @Test
  void structureShouldEscapeExactSchemaAndTablePatterns() throws Exception {
    JdbcMetadataManagementServiceImpl service = createService();

    when(dialect.loadColumns(any(), any(), any(), any(), any(), any(), any())).thenReturn(
        new JdbcDatabaseDialect.MetadataResult(List.of(), List.of())
    );
    when(dialect.loadConstraints(any(), any(), any(), any(), any())).thenReturn(List.of());
    when(dialect.code()).thenReturn("postgresql");
    when(dialect.name()).thenReturn("PostgreSQL");

    service.structure("ds-1", new JdbcMetadataRequests.PathRequest(List.of(
        new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.DATABASE, "app_suite"),
        new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.SCHEMA, "tenant_%"),
        new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, "order_%_items")
    )));

    verify(dialect).loadColumns(
        eq(connection),
        eq(metaData),
        any(),
        eq("app_suite"),
        eq("tenant\\_\\%"),
        eq("order\\_\\%\\_items"),
        eq("%")
    );
  }

  private JdbcMetadataManagementServiceImpl createService() throws Exception {
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
    when(connection.getSchema()).thenReturn("tenant_%");
    when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(metaData.getDatabaseProductVersion()).thenReturn("16");
    when(metaData.getIdentifierQuoteString()).thenReturn("\"");
    when(metaData.getSearchStringEscape()).thenReturn("\\");

    return new JdbcMetadataManagementServiceImpl(
        dataSourceService,
        driverRepository,
        dialectManagementService
    );
  }
}
