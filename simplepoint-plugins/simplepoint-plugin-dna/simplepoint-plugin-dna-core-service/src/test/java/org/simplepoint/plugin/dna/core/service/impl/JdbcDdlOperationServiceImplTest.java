package org.simplepoint.plugin.dna.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
class JdbcDdlOperationServiceImplTest {

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
  void createNamespace_withNullName_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.createNamespace("ds-1",
            new JdbcMetadataRequests.NamespaceCreateRequest(JdbcMetadataModels.NodeType.SCHEMA, null, null))
    );
    assertTrue(ex.getMessage().contains("命名空间名称不能为空"));
  }

  @Test
  void createNamespace_withNullType_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.createNamespace("ds-1",
            new JdbcMetadataRequests.NamespaceCreateRequest(null, null, "my_schema"))
    );
    assertTrue(ex.getMessage().contains("命名空间类型不能为空"));
  }

  @Test
  void drop_withRootPath_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.drop("ds-1", new JdbcMetadataRequests.DropRequest(null, false))
    );
    assertTrue(ex.getMessage().contains("当前路径不支持删除操作"));
  }

  @Test
  void createTable_withNullName_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.createTable("ds-1",
            new JdbcMetadataRequests.TableCreateRequest(null, null, List.of(), List.of()))
    );
    assertTrue(ex.getMessage().contains("数据表名称不能为空"));
  }

  @Test
  void createView_withNullSql_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.createView("ds-1",
            new JdbcMetadataRequests.ViewCreateRequest(null, "my_view", null))
    );
    assertTrue(ex.getMessage().contains("视图SQL不能为空"));
  }

  @Test
  void addColumn_withNullColumn_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.addColumn("ds-1",
            new JdbcMetadataRequests.ColumnAddRequest(
                List.of(new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, "my_table")),
                null
            ))
    );
    assertTrue(ex.getMessage().contains("字段定义不能为空"));
  }

  @Test
  void alterColumn_withNullCurrentName_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();
    JdbcMetadataModels.ColumnDefinition column = new JdbcMetadataModels.ColumnDefinition(
        "new_name", "VARCHAR", 255, null, null, null, null, null
    );

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.alterColumn("ds-1",
            new JdbcMetadataRequests.ColumnAlterRequest(
                List.of(new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, "my_table")),
                null,
                column
            ))
    );
    assertTrue(ex.getMessage().contains("当前字段名称不能为空"));
  }

  @Test
  void dropColumn_withNullColumnName_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.dropColumn("ds-1",
            new JdbcMetadataRequests.ColumnDropRequest(
                List.of(new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, "my_table")),
                null
            ))
    );
    assertTrue(ex.getMessage().contains("字段名称不能为空"));
  }

  @Test
  void addConstraint_withNullConstraint_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.addConstraint("ds-1",
            new JdbcMetadataRequests.ConstraintAddRequest(
                List.of(new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, "my_table")),
                null
            ))
    );
    assertTrue(ex.getMessage().contains("约束定义不能为空"));
  }

  @Test
  void dropConstraint_withNullType_throwsIllegalArgumentException() throws Exception {
    JdbcDdlOperationServiceImpl service = createService();

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.dropConstraint("ds-1",
            new JdbcMetadataRequests.ConstraintDropRequest(
                List.of(new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, "my_table")),
                "pk_my_table",
                null
            ))
    );
    assertTrue(ex.getMessage().contains("约束类型不能为空"));
  }

  private JdbcDdlOperationServiceImpl createService() throws Exception {
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

    return new JdbcDdlOperationServiceImpl(dataSourceService, driverRepository, dialectManagementService);
  }
}
