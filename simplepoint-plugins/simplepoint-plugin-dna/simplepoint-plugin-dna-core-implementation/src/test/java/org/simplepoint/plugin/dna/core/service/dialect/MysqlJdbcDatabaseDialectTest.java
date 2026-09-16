package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

class MysqlJdbcDatabaseDialectTest {

  @Test
  void shouldUseJdbcIdentifierQuoteStringForQualifiedNamesAndConstraints() {
    MysqlJdbcDatabaseDialect dialect = new MysqlJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "mysql",
        "com.mysql.cj.jdbc.Driver",
        "MySQL",
        "8.0",
        "appsuite",
        null,
        "`",
        Map.of()
    );

    String qualifiedName = dialect.qualifyName("appsuite", null, "example_users", context);
    String sql = dialect.buildCreateTableSql(
        qualifiedName,
        List.of(new JdbcMetadataModels.ColumnDefinition("id", "BIGINT", null, null, false, null, null, null)),
        List.of(new JdbcMetadataModels.ConstraintDefinition(
            "pk_example_users",
            JdbcMetadataModels.ConstraintType.PRIMARY_KEY,
            List.of("id"),
            null,
            null
        )),
        context
    );

    assertEquals("`appsuite`.`example_users`", qualifiedName);
    assertTrue(sql.contains("CONSTRAINT `pk_example_users` PRIMARY KEY (`id`)"));
  }

  @Test
  void shouldRouteMetadataUsingCatalogWithoutSchema() throws Exception {
    MysqlJdbcDatabaseDialect dialect = new MysqlJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "mysql",
        "com.mysql.cj.jdbc.Driver",
        "MySQL",
        "8.0",
        "appsuite",
        null,
        "`",
        Map.of()
    );
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet tablesResultSet = emptyResultSet("TABLE_NAME", "TABLE_TYPE");
    ResultSet columnsResultSet = emptyResultSet("COLUMN_NAME");
    when(metaData.supportsCatalogsInTableDefinitions()).thenReturn(true);
    when(metaData.supportsSchemasInTableDefinitions()).thenReturn(false);
    when(metaData.getTables(eq("appsuite"), isNull(), eq("users"), org.mockito.ArgumentMatchers.<String[]>any()))
        .thenReturn(tablesResultSet);
    when(metaData.getColumns(eq("appsuite"), isNull(), eq("users"), eq("%")))
        .thenReturn(columnsResultSet);

    JdbcDatabaseDialect.MetadataNamespaceSupport namespaceSupport =
        dialect.resolveMetadataNamespaceSupport(null, metaData, context);
    dialect.loadTables(null, metaData, context, "appsuite", "ignored_schema", "users", List.of("TABLE"));
    dialect.loadColumns(null, metaData, context, "appsuite", "ignored_schema", "users", "%");

    assertTrue(namespaceSupport.supportsCatalogsInTableDefinitions());
    assertFalse(namespaceSupport.supportsSchemasInTableDefinitions());

    ArgumentCaptor<String[]> tableTypes = ArgumentCaptor.forClass(String[].class);
    verify(metaData).getTables(eq("appsuite"), isNull(), eq("users"), tableTypes.capture());
    assertEquals(List.of("TABLE", "BASE TABLE"), List.of(tableTypes.getValue()));
    verify(metaData).getColumns(eq("appsuite"), isNull(), eq("users"), eq("%"));
  }

  private static ResultSet emptyResultSet(final String... columnLabels) throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(resultSet.getMetaData()).thenReturn(metaData);
    when(resultSet.next()).thenReturn(false);
    when(metaData.getColumnCount()).thenReturn(columnLabels.length);
    for (int index = 0; index < columnLabels.length; index++) {
      when(metaData.getColumnLabel(index + 1)).thenReturn(columnLabels[index]);
      when(metaData.getColumnName(index + 1)).thenReturn(columnLabels[index]);
      when(metaData.getColumnTypeName(index + 1)).thenReturn("VARCHAR");
      when(metaData.getColumnType(index + 1)).thenReturn(java.sql.Types.VARCHAR);
    }
    return resultSet;
  }
}
