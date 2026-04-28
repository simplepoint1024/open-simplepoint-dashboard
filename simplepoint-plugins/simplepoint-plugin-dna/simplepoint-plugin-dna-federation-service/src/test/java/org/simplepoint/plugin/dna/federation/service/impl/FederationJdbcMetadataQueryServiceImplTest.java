package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcDriverModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationJdbcMetadataSupport;

@ExtendWith(MockitoExtension.class)
class FederationJdbcMetadataQueryServiceImplTest {

  @Mock
  private FederationJdbcMetadataSupport jdbcMetadataSupport;

  private FederationJdbcMetadataQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new FederationJdbcMetadataQueryServiceImpl(jdbcMetadataSupport);
  }

  // ── catalogs ──────────────────────────────────────────────────────────────

  @Test
  void catalogsShouldReturnCodesSorted() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "zebra");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "alpha");

    FederationJdbcDriverModels.TabularResult result = service.catalogs(List.of(ds1, ds2));

    assertThat(result.rows()).containsExactly(List.of("alpha"), List.of("zebra"));
  }

  @Test
  void catalogsShouldReturnEmptyForNullInput() {
    FederationJdbcDriverModels.TabularResult result = service.catalogs(null);

    assertThat(result.rows()).isEmpty();
    assertThat(result.columns()).hasSize(1);
  }

  @Test
  void catalogsShouldReturnEmptyForEmptyList() {
    FederationJdbcDriverModels.TabularResult result = service.catalogs(List.of());

    assertThat(result.rows()).isEmpty();
  }

  // ── schemas ───────────────────────────────────────────────────────────────

  @Test
  void schemasShouldDelegateToSupportForMatchingCatalog() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "mydb");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "other");
    FederationJdbcDriverModels.TabularResult expected = tabularResult("TABLE_SCHEM", "public");
    when(jdbcMetadataSupport.schemas(ds1, "mydb", "%")).thenReturn(expected);

    FederationJdbcDriverModels.TabularResult result = service.schemas(List.of(ds1, ds2), "mydb", "%");

    assertThat(result).isEqualTo(expected);
    verify(jdbcMetadataSupport).schemas(ds1, "mydb", "%");
    verify(jdbcMetadataSupport, never()).schemas(ds2, "mydb", "%");
  }

  @Test
  void schemasShouldUseFallbackDataSourceWhenNoCatalogMatches() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "db1");
    FederationJdbcDriverModels.TabularResult fallback = tabularResult("TABLE_SCHEM", "information_schema");
    when(jdbcMetadataSupport.schemas(ds1, "__simplepoint_no_match__", null)).thenReturn(fallback);

    FederationJdbcDriverModels.TabularResult result = service.schemas(List.of(ds1), "nonexistent", null);

    assertThat(result).isEqualTo(fallback);
    verify(jdbcMetadataSupport).schemas(ds1, "__simplepoint_no_match__", null);
  }

  @Test
  void schemasShouldAggregateAllDataSourcesWhenCatalogPatternIsNull() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "db1");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "db2");
    FederationJdbcDriverModels.TabularResult res1 = tabularResult("TABLE_SCHEM", "s1");
    FederationJdbcDriverModels.TabularResult res2 = tabularResult("TABLE_SCHEM", "s2");
    when(jdbcMetadataSupport.schemas(ds1, null, null)).thenReturn(res1);
    when(jdbcMetadataSupport.schemas(ds2, null, null)).thenReturn(res2);

    FederationJdbcDriverModels.TabularResult result = service.schemas(List.of(ds1, ds2), null, null);

    assertThat(result.rows()).containsExactlyInAnyOrder(List.of("s1"), List.of("s2"));
  }

  @Test
  void schemasShouldReturnEmptyForNullDataSources() {
    FederationJdbcDriverModels.TabularResult result = service.schemas(null, "mydb", null);

    assertThat(result.rows()).isEmpty();
  }

  // ── tableTypes ────────────────────────────────────────────────────────────

  @Test
  void tableTypesShouldDeduplicateAcrossDataSources() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "db1");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "db2");
    FederationJdbcDriverModels.TabularResult res1 = tabularResult("TABLE_TYPE", "TABLE");
    FederationJdbcDriverModels.TabularResult res2 = tabularResult("TABLE_TYPE", "TABLE");
    when(jdbcMetadataSupport.tableTypes(ds1)).thenReturn(res1);
    when(jdbcMetadataSupport.tableTypes(ds2)).thenReturn(res2);

    FederationJdbcDriverModels.TabularResult result = service.tableTypes(List.of(ds1, ds2));

    assertThat(result.rows()).containsExactly(List.of("TABLE"));
  }

  // ── tables ────────────────────────────────────────────────────────────────

  @Test
  void tablesShouldFilterByCatalogAndDelegateToSupport() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "ds1");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "ds2");
    FederationJdbcDriverModels.TabularResult expected = tabularResult("TABLE_NAME", "orders");
    when(jdbcMetadataSupport.tables(ds1, "ds1", "public", "%", List.of("TABLE"))).thenReturn(expected);

    FederationJdbcDriverModels.TabularResult result = service.tables(
        List.of(ds1, ds2), "ds1", "public", "%", List.of("TABLE"));

    assertThat(result).isEqualTo(expected);
    verify(jdbcMetadataSupport).tables(ds1, "ds1", "public", "%", List.of("TABLE"));
    verify(jdbcMetadataSupport, never()).tables(ds2, "ds1", "public", "%", List.of("TABLE"));
  }

  @Test
  void tablesShouldReturnEmptyForNullDataSources() {
    FederationJdbcDriverModels.TabularResult result = service.tables(null, "ds1", null, "%", null);

    assertThat(result.rows()).isEmpty();
  }

  // ── columns ───────────────────────────────────────────────────────────────

  @Test
  void columnsShouldFilterByCatalogAndDelegateToSupport() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "mydb");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "other");
    FederationJdbcDriverModels.TabularResult expected = tabularResult("COLUMN_NAME", "id");
    when(jdbcMetadataSupport.columns(ds1, "mydb", "public", "users", "%")).thenReturn(expected);

    FederationJdbcDriverModels.TabularResult result = service.columns(
        List.of(ds1, ds2), "mydb", "public", "users", "%");

    assertThat(result).isEqualTo(expected);
    verify(jdbcMetadataSupport, never()).columns(ds2, "mydb", "public", "users", "%");
  }

  // ── primaryKeys ───────────────────────────────────────────────────────────

  @Test
  void primaryKeysShouldReturnEmptyWhenNoCatalogMatches() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "db1");

    FederationJdbcDriverModels.TabularResult result = service.primaryKeys(
        List.of(ds1), "nonexistent", "public", "users");

    assertThat(result.rows()).isEmpty();
  }

  @Test
  void primaryKeysShouldDelegateToSupportForMatchingCatalog() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "mydb");
    FederationJdbcDriverModels.TabularResult expected = tabularResult("COLUMN_NAME", "id");
    when(jdbcMetadataSupport.primaryKeys(ds1, "mydb", "public", "users")).thenReturn(expected);

    FederationJdbcDriverModels.TabularResult result = service.primaryKeys(
        List.of(ds1), "mydb", "public", "users");

    assertThat(result).isEqualTo(expected);
  }

  // ── indexInfo ─────────────────────────────────────────────────────────────

  @Test
  void indexInfoShouldReturnEmptyWhenNoCatalogMatches() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "db1");

    FederationJdbcDriverModels.TabularResult result = service.indexInfo(
        List.of(ds1), "nonexistent", "public", "orders", true, false);

    assertThat(result.rows()).isEmpty();
  }

  @Test
  void indexInfoShouldDelegateToSupportForMatchingCatalog() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "mydb");
    FederationJdbcDriverModels.TabularResult expected = tabularResult("INDEX_NAME", "pk_id");
    when(jdbcMetadataSupport.indexInfo(ds1, "mydb", "public", "orders", false, true)).thenReturn(expected);

    FederationJdbcDriverModels.TabularResult result = service.indexInfo(
        List.of(ds1), "mydb", "public", "orders", false, true);

    assertThat(result).isEqualTo(expected);
  }

  // ── importedKeys ──────────────────────────────────────────────────────────

  @Test
  void importedKeysShouldReturnEmptyWhenNoCatalogMatches() {
    JdbcDataSourceDefinition ds = dataSource("ds-1", "db1");

    FederationJdbcDriverModels.TabularResult result = service.importedKeys(
        List.of(ds), "other", null, "orders");

    assertThat(result.rows()).isEmpty();
  }

  @Test
  void importedKeysShouldDelegateForMatchingCatalog() {
    JdbcDataSourceDefinition ds = dataSource("ds-1", "mydb");
    FederationJdbcDriverModels.TabularResult expected = tabularResult("FK_NAME", "fk_user_id");
    when(jdbcMetadataSupport.importedKeys(ds, "mydb", null, "orders")).thenReturn(expected);

    FederationJdbcDriverModels.TabularResult result = service.importedKeys(
        List.of(ds), "mydb", null, "orders");

    assertThat(result).isEqualTo(expected);
  }

  // ── exportedKeys ──────────────────────────────────────────────────────────

  @Test
  void exportedKeysShouldReturnEmptyWhenNoCatalogMatches() {
    JdbcDataSourceDefinition ds = dataSource("ds-1", "db1");

    FederationJdbcDriverModels.TabularResult result = service.exportedKeys(
        List.of(ds), "other", null, "users");

    assertThat(result.rows()).isEmpty();
  }

  @Test
  void exportedKeysShouldDelegateForMatchingCatalog() {
    JdbcDataSourceDefinition ds = dataSource("ds-1", "mydb");
    FederationJdbcDriverModels.TabularResult expected = tabularResult("FK_NAME", "fk_ref");
    when(jdbcMetadataSupport.exportedKeys(ds, "mydb", null, "users")).thenReturn(expected);

    FederationJdbcDriverModels.TabularResult result = service.exportedKeys(
        List.of(ds), "mydb", null, "users");

    assertThat(result).isEqualTo(expected);
  }

  // ── typeInfo ──────────────────────────────────────────────────────────────

  @Test
  void typeInfoShouldDeduplicateAcrossDataSources() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "db1");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "db2");
    FederationJdbcDriverModels.TabularResult res = tabularResult("TYPE_NAME", "INTEGER");
    when(jdbcMetadataSupport.typeInfo(ds1)).thenReturn(res);
    when(jdbcMetadataSupport.typeInfo(ds2)).thenReturn(res);

    FederationJdbcDriverModels.TabularResult result = service.typeInfo(List.of(ds1, ds2));

    assertThat(result.rows()).containsExactly(List.of("INTEGER"));
  }

  // ── filterByCatalogPattern static helper ──────────────────────────────────

  @Test
  void filterByCatalogPatternShouldReturnAllWhenPatternIsNull() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "db1");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "db2");

    List<JdbcDataSourceDefinition> result =
        FederationJdbcMetadataQueryServiceImpl.filterByCatalogPattern(List.of(ds1, ds2), null);

    assertThat(result).containsExactly(ds1, ds2);
  }

  @Test
  void filterByCatalogPatternShouldFilterCaseInsensitively() {
    JdbcDataSourceDefinition ds1 = dataSource("ds-1", "MyDB");
    JdbcDataSourceDefinition ds2 = dataSource("ds-2", "other");

    List<JdbcDataSourceDefinition> result =
        FederationJdbcMetadataQueryServiceImpl.filterByCatalogPattern(List.of(ds1, ds2), "mydb");

    assertThat(result).containsExactly(ds1);
  }

  @Test
  void filterByCatalogPatternShouldReturnEmptyForNullList() {
    List<JdbcDataSourceDefinition> result =
        FederationJdbcMetadataQueryServiceImpl.filterByCatalogPattern(null, "mydb");

    assertThat(result).isEmpty();
  }

  // ── mergeTabularResults static helper ─────────────────────────────────────

  @Test
  void mergeTabularResultsShouldCombineRowsFromMultipleResults() {
    FederationJdbcDriverModels.TabularResult r1 = tabularResult("COL", "row1");
    FederationJdbcDriverModels.TabularResult r2 = tabularResult("COL", "row2");

    FederationJdbcDriverModels.TabularResult merged =
        FederationJdbcMetadataQueryServiceImpl.mergeTabularResults(List.of(r1, r2));

    assertThat(merged.rows()).containsExactly(List.of("row1"), List.of("row2"));
  }

  @Test
  void mergeTabularResultsShouldHandleNullResults() {
    FederationJdbcDriverModels.TabularResult r1 = tabularResult("COL", "row1");

    FederationJdbcDriverModels.TabularResult merged =
        FederationJdbcMetadataQueryServiceImpl.mergeTabularResults(Arrays.asList(r1, null));

    assertThat(merged.rows()).containsExactly(List.of("row1"));
  }

  @Test
  void mergeTabularResultsShouldReturnEmptyForNullInput() {
    FederationJdbcDriverModels.TabularResult merged =
        FederationJdbcMetadataQueryServiceImpl.mergeTabularResults(null);

    assertThat(merged.rows()).isEmpty();
  }

  // ── deduplicateRows static helper ─────────────────────────────────────────

  @Test
  void deduplicateRowsShouldRemoveDuplicates() {
    FederationJdbcDriverModels.TabularResult input = new FederationJdbcDriverModels.TabularResult(
        List.of(new FederationJdbcDriverModels.JdbcColumn("T", "VARCHAR", Types.VARCHAR)),
        List.of(List.of("TABLE"), List.of("TABLE"), List.of("VIEW"))
    );

    FederationJdbcDriverModels.TabularResult result =
        FederationJdbcMetadataQueryServiceImpl.deduplicateRows(input);

    assertThat(result.rows()).containsExactly(List.of("TABLE"), List.of("VIEW"));
  }

  @Test
  void deduplicateRowsShouldReturnEmptyForNullInput() {
    FederationJdbcDriverModels.TabularResult result =
        FederationJdbcMetadataQueryServiceImpl.deduplicateRows(null);

    assertThat(result.rows()).isEmpty();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static JdbcDataSourceDefinition dataSource(final String id, final String code) {
    JdbcDataSourceDefinition ds = new JdbcDataSourceDefinition();
    ds.setId(id);
    ds.setCode(code);
    ds.setName("DataSource " + code);
    return ds;
  }

  private static FederationJdbcDriverModels.TabularResult tabularResult(
      final String columnName, final String singleRowValue
  ) {
    return new FederationJdbcDriverModels.TabularResult(
        List.of(new FederationJdbcDriverModels.JdbcColumn(columnName, "VARCHAR", Types.VARCHAR)),
        List.of(List.of(singleRowValue))
    );
  }
}
