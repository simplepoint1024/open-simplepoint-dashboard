package org.simplepoint.plugin.dna.core.service.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class OracleJdbcDatabaseDialectTest {

  @Test
  void shouldUseOracleSpecificMetadataAndAlterSql() {
    OracleJdbcDatabaseDialect dialect = new OracleJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "oracle",
        "oracle.jdbc.OracleDriver",
        "Oracle",
        "21c",
        null,
        "APP",
        "\"",
        Map.of()
    );

    assertTrue(dialect.supports(context));
    assertNull(dialect.metadataCatalog("XE", context));
    assertFalse(dialect.supportsNamespaceCreate(JdbcMetadataModels.NodeType.SCHEMA));
    assertEquals(
        "SELECT * FROM \"APP\".\"USERS\" ORDER BY 1 OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
        dialect.buildPreviewSql("\"APP\".\"USERS\"", 20, 10)
    );
    assertEquals(
        "ALTER TABLE \"APP\".\"USERS\" ADD (\"EMAIL\" VARCHAR2(128) DEFAULT 'n/a')",
        dialect.buildAddColumnSql(
            "\"APP\".\"USERS\"",
            new JdbcMetadataModels.ColumnDefinition("EMAIL", "VARCHAR2", 128, null, true, "'n/a'", null, null),
            context
        )
    );
    assertEquals(
        List.of(
            "ALTER TABLE \"APP\".\"USERS\" RENAME COLUMN \"MAIL\" TO \"EMAIL\"",
            "ALTER TABLE \"APP\".\"USERS\" MODIFY (\"EMAIL\" VARCHAR2(128) DEFAULT 'n/a')"
        ),
        dialect.buildAlterColumnSql(
            "\"APP\".\"USERS\"",
            "MAIL",
            new JdbcMetadataModels.ColumnDefinition("EMAIL", "VARCHAR2", 128, null, true, "'n/a'", null, null),
            context
        )
    );
  }

  @Test
  void shouldRouteMetadataUsingSchemaWithoutCatalog() throws Exception {
    OracleJdbcDatabaseDialect dialect = new OracleJdbcDatabaseDialect();
    JdbcDatabaseDialect.SupportContext context = new JdbcDatabaseDialect.SupportContext(
        "oracle",
        "oracle.jdbc.OracleDriver",
        "Oracle",
        "21c",
        null,
        "APP",
        "\"",
        Map.of()
    );
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet tablesResultSet = emptyResultSet("TABLE_NAME", "TABLE_TYPE");
    ResultSet columnsResultSet = emptyResultSet("COLUMN_NAME");
    when(metaData.supportsCatalogsInTableDefinitions()).thenReturn(false);
    when(metaData.supportsSchemasInTableDefinitions()).thenReturn(true);
    when(metaData.getTables(isNull(), eq("APP"), eq("USERS"), org.mockito.ArgumentMatchers.<String[]>any()))
        .thenReturn(tablesResultSet);
    when(metaData.getColumns(isNull(), eq("APP"), eq("USERS"), eq("%")))
        .thenReturn(columnsResultSet);

    JdbcDatabaseDialect.MetadataNamespaceSupport namespaceSupport =
        dialect.resolveMetadataNamespaceSupport(null, metaData, context);
    dialect.loadTables(null, metaData, context, "IGNORED", "APP", "USERS", List.of("TABLE"));
    dialect.loadColumns(null, metaData, context, "IGNORED", "APP", "USERS", "%");

    assertFalse(namespaceSupport.supportsCatalogsInTableDefinitions());
    assertTrue(namespaceSupport.supportsSchemasInTableDefinitions());

    ArgumentCaptor<String[]> tableTypes = ArgumentCaptor.forClass(String[].class);
    verify(metaData).getTables(isNull(), eq("APP"), eq("USERS"), tableTypes.capture());
    assertEquals(List.of("TABLE", "BASE TABLE"), List.of(tableTypes.getValue()));
    verify(metaData).getColumns(isNull(), eq("APP"), eq("USERS"), eq("%"));
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
