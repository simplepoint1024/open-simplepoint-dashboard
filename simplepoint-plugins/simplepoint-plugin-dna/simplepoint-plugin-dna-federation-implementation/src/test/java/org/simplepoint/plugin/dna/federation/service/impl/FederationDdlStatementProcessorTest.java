package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlStatementProcessor.DdlTarget;

@ExtendWith(MockitoExtension.class)
class FederationDdlStatementProcessorTest {

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @InjectMocks
  private FederationDdlStatementProcessor processor;

  // ----- resolve() -----

  @Test
  void resolveShouldReturnSelectedDataSourceWhenNoTableNameInSql() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "myds");
    DdlTarget target = processor.resolve(selected, "CREATE INDEX idx_users ON something");
    assertEquals("ds-1", target.dataSource().getId());
  }

  @Test
  void resolveShouldReturnSelectedDataSourceForUnqualifiedTableName() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "myds");
    DdlTarget target = processor.resolve(selected, "CREATE TABLE users (id INT)");
    assertEquals("ds-1", target.dataSource().getId());
  }

  @Test
  void resolveShouldMatchDataSourceFromQualifiedTableName() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "myds");
    JdbcDataSourceDefinition other = dataSource("ds-2", "otherdb");
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));

    DdlTarget target = processor.resolve(selected, "CREATE TABLE otherdb.users (id INT)");

    assertEquals("ds-2", target.dataSource().getId());
  }

  @Test
  void resolveShouldReturnSelectedDataSourceWhenPrefixNotFound() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "myds");
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected));

    DdlTarget target = processor.resolve(selected, "DROP TABLE unknown_db.orders");

    assertEquals("ds-1", target.dataSource().getId());
  }

  @Test
  void resolveShouldHandleAlterTable() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "myds");
    JdbcDataSourceDefinition other = dataSource("ds-2", "pg");
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));

    DdlTarget target = processor.resolve(selected, "ALTER TABLE pg.orders ADD COLUMN note VARCHAR(255)");

    assertEquals("ds-2", target.dataSource().getId());
  }

  @Test
  void resolveShouldHandleDropTable() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "myds");
    JdbcDataSourceDefinition other = dataSource("ds-2", "pg");
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));

    DdlTarget target = processor.resolve(selected, "DROP TABLE IF EXISTS pg.tmp_table");

    assertEquals("ds-2", target.dataSource().getId());
  }

  // ----- rewrite() -----

  @Test
  void rewriteShouldStripDataSourcePrefixFromCreateTable() {
    String sql = "CREATE TABLE myds.users (id INT, name VARCHAR(100))";
    String result = FederationDdlStatementProcessor.rewrite(sql, "myds");
    assertFalse(result.contains("myds."), result);
    assertTrue(result.contains("users"), result);
  }

  @Test
  void rewriteShouldReturnUnchangedWhenPrefixDoesNotMatch() {
    String sql = "CREATE TABLE otherdb.users (id INT)";
    String result = FederationDdlStatementProcessor.rewrite(sql, "myds");
    assertEquals(sql, result);
  }

  @Test
  void rewriteShouldHandleDropTable() {
    String sql = "DROP TABLE myds.old_table";
    String result = FederationDdlStatementProcessor.rewrite(sql, "myds");
    assertFalse(result.contains("myds."), result);
    assertTrue(result.contains("old_table"), result);
  }

  @Test
  void rewriteShouldReturnNullForNullSql() {
    assertEquals(null, FederationDdlStatementProcessor.rewrite(null, "ds"));
  }

  @Test
  void rewriteShouldReturnSqlWhenDataSourceCodeIsNull() {
    String sql = "CREATE TABLE myds.users (id INT)";
    assertEquals(sql, FederationDdlStatementProcessor.rewrite(sql, null));
  }

  @Test
  void rewriteShouldHandleSchemaQualifiedName() {
    // datasource.schema.table — strip only the datasource prefix
    String sql = "ALTER TABLE myds.public.orders ADD COLUMN note TEXT";
    String result = FederationDdlStatementProcessor.rewrite(sql, "myds");
    assertFalse(result.contains("myds."), result);
    assertTrue(result.contains("public.orders"), result);
  }

  // ----- stripQuotes() -----

  @Test
  void stripQuotesShouldRemoveDoubleQuotes() {
    assertEquals("myTable", FederationDdlStatementProcessor.stripQuotes("\"myTable\""));
  }

  @Test
  void stripQuotesShouldRemoveBackticks() {
    assertEquals("myTable", FederationDdlStatementProcessor.stripQuotes("`myTable`"));
  }

  @Test
  void stripQuotesShouldRemoveSquareBrackets() {
    assertEquals("myTable", FederationDdlStatementProcessor.stripQuotes("[myTable]"));
  }

  @Test
  void stripQuotesShouldReturnUnquotedIdentifierUnchanged() {
    assertEquals("myTable", FederationDdlStatementProcessor.stripQuotes("myTable"));
  }

  @Test
  void stripQuotesShouldReturnNullForNull() {
    assertEquals(null, FederationDdlStatementProcessor.stripQuotes(null));
  }

  @Test
  void stripQuotesShouldReturnShortStringUnchanged() {
    assertEquals("x", FederationDdlStatementProcessor.stripQuotes("x"));
  }

  // ----- DDL_PREFIX_PATTERN -----

  @Test
  void ddlPrefixPatternShouldMatchCreateAlterDropTruncate() {
    assertTrue(FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher("CREATE TABLE t (id INT)").lookingAt());
    assertTrue(FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher("ALTER TABLE t ADD COLUMN x INT").lookingAt());
    assertTrue(FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher("DROP TABLE t").lookingAt());
    assertTrue(FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher("TRUNCATE TABLE t").lookingAt());
  }

  @Test
  void ddlPrefixPatternShouldNotMatchDmlKeywords() {
    assertFalse(FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher("SELECT 1").lookingAt());
    assertFalse(FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher("INSERT INTO t VALUES (1)").lookingAt());
    assertFalse(FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher("UPDATE t SET x = 1").lookingAt());
  }

  // ----- helpers -----

  private static JdbcDataSourceDefinition dataSource(final String id, final String code) {
    JdbcDataSourceDefinition ds = new JdbcDataSourceDefinition();
    ds.setId(id);
    ds.setCode(code);
    ds.setEnabled(true);
    return ds;
  }
}
