package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDmlStatementProcessor.DmlTarget;
import org.simplepoint.plugin.dna.federation.service.impl.FederationSqlAnalysisUtils.TableReferenceSummary;

@ExtendWith(MockitoExtension.class)
class FederationDmlStatementProcessorTest {

  @Mock private JdbcDataSourceDefinitionService dataSourceService;

  @InjectMocks private FederationDmlStatementProcessor processor;

  // ----- resolve() -----

  @Test
  void resolveShouldRejectMissingTableFootprint() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "ds1");
    TableReferenceSummary empty = TableReferenceSummary.empty();

    assertThrows(FederationSqlWriteException.class, () -> processor.resolve(selected, empty));
  }

  @Test
  void resolveShouldReturnSelectedDataSourceForUnqualifiedIdentifiers() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "ds1");
    // single-segment identifier → no datasource prefix
    TableReferenceSummary refs = new TableReferenceSummary(List.of(List.of("users")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected));

    DmlTarget target = processor.resolve(selected, refs);

    assertEquals("ds-1", target.dataSource().getId());
  }

  @Test
  void resolveShouldMatchExplicitDataSourceFromQualifiedIdentifier() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "ds1");
    JdbcDataSourceDefinition other = dataSource("ds-2", "other");
    // two-segment identifier: datasource.table
    TableReferenceSummary refs = new TableReferenceSummary(List.of(List.of("other", "orders")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));

    DmlTarget target = processor.resolve(selected, refs);

    assertEquals("ds-2", target.dataSource().getId());
  }

  @Test
  void resolveShouldThrowWhenMultipleDataSourcesDetected() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "ds1");
    JdbcDataSourceDefinition other = dataSource("ds-2", "other");
    TableReferenceSummary refs =
        new TableReferenceSummary(List.of(List.of("ds1", "orders"), List.of("other", "customers")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));

    FederationSqlWriteException ex =
        assertThrows(FederationSqlWriteException.class, () -> processor.resolve(selected, refs));

    assertEquals(FederationSqlWriteException.Code.SQL_WRITE_SCOPE_INVALID, ex.errorCode());
  }

  @Test
  void insertSelectMustRejectAnotherSource() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "ds1");
    JdbcDataSourceDefinition other = dataSource("ds-2", "ds2");
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected, other));
    TableReferenceSummary references =
        FederationSqlAnalysisUtils.collectTableReferences(
            "INSERT INTO ds1.orders (id) SELECT id FROM ds2.orders");

    assertThrows(FederationSqlWriteException.class, () -> processor.resolve(selected, references));
  }

  @Test
  void resolveShouldFallBackToSelectedDataSourceWhenCodeNotFound() {
    JdbcDataSourceDefinition selected = dataSource("ds-1", "ds1");
    // Qualified identifier whose prefix doesn't match any known datasource
    TableReferenceSummary refs = new TableReferenceSummary(List.of(List.of("unknown_ds", "users")));
    when(dataSourceService.listEnabledDefinitions()).thenReturn(List.of(selected));

    DmlTarget target = processor.resolve(selected, refs);

    assertEquals("ds-1", target.dataSource().getId());
  }

  @Test
  void duplicateDatasourceCodesMustNotSelectAnArbitraryProvider() {
    var selected = dataSource("ds-1", "ds1");
    when(dataSourceService.listEnabledDefinitions())
        .thenReturn(List.of(selected, dataSource("ds-2", "DS1")));
    var failure =
        assertThrows(
            FederationSqlWriteException.class,
            () ->
                processor.resolve(
                    selected, FederationDmlPlan.parse("DELETE FROM ds1.orders").references()));
    assertEquals(FederationSqlWriteException.Code.SQL_WRITE_SCOPE_INVALID, failure.errorCode());
  }

  // ----- rewrite() -----

  @Test
  void relationalCompatibilityKeepsUpdatesAndDeletesAndPreservesNativeInsertDefaults() {
    assertFalse(
        FederationDmlStatementProcessor.canExecuteWithCalcite(
            "INSERT INTO ds1.orders (id) VALUES (1)"));
    assertTrue(
        FederationDmlStatementProcessor.canExecuteWithCalcite(
            "UPDATE ds1.orders SET id = 2 WHERE id = 1"));
    assertTrue(
        FederationDmlStatementProcessor.canExecuteWithCalcite(
            "DELETE FROM ds1.orders WHERE id = 1"));
    assertFalse(
        FederationDmlStatementProcessor.canExecuteWithCalcite(
            "UPSERT INTO ds1.orders (id) VALUES (1)"));
    assertFalse(
        FederationDmlStatementProcessor.canExecuteWithCalcite(
            "MERGE INTO ds1.orders AS target USING ds1.incoming AS source ON target.id = source.id "
                + "WHEN MATCHED THEN UPDATE SET id = source.id"));
    assertThrows(
        FederationSqlWriteException.class,
        () ->
            FederationDmlStatementProcessor.canExecuteWithCalcite(
                "DELETE FROM ds1.orders; DELETE FROM ds1.orders"));
  }

  @Test
  void rewriteShouldStripDataSourcePrefixFromInsert() {
    String sql = "INSERT INTO myds.users (id, name) VALUES (1, 'Alice')";
    String result = FederationDmlStatementProcessor.rewrite(sql, "myds");
    org.junit.jupiter.api.Assertions.assertFalse(result.toLowerCase().contains("myds."), result);
    org.junit.jupiter.api.Assertions.assertTrue(result.toLowerCase().contains("users"), result);
  }

  @Test
  void rewriteShouldReturnUnchangedSqlWhenPrefixDoesNotMatch() {
    String sql = "INSERT INTO other.users (id) VALUES (1)";
    String result = FederationDmlStatementProcessor.rewrite(sql, "myds");
    assertEquals(sql, result);
  }

  @Test
  void rewriteShouldRejectUnparseableSql() {
    String badSql = "NOT VALID SQL %%%";
    assertThrows(
        FederationSqlWriteException.class,
        () -> FederationDmlStatementProcessor.rewrite(badSql, "myds"));
  }

  @Test
  void rewriteShouldHandleNullSql() {
    String result = FederationDmlStatementProcessor.rewrite(null, "myds");
    assertEquals(null, result);
  }

  @Test
  void rewriteShouldHandleUpdateStatement() {
    String sql = "UPDATE ds1.orders SET status = 'done' WHERE id = 1";
    String result = FederationDmlStatementProcessor.rewrite(sql, "ds1");
    org.junit.jupiter.api.Assertions.assertFalse(result.toLowerCase().contains("ds1."), result);
    org.junit.jupiter.api.Assertions.assertTrue(result.toLowerCase().contains("orders"), result);
  }

  @Test
  void rewriteShouldHandleDeleteStatement() {
    String sql = "DELETE FROM myds.products WHERE id = 5";
    String result = FederationDmlStatementProcessor.rewrite(sql, "myds");
    org.junit.jupiter.api.Assertions.assertFalse(
        result.toLowerCase().contains("myds.products"), result);
    org.junit.jupiter.api.Assertions.assertTrue(result.toLowerCase().contains("products"), result);
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
