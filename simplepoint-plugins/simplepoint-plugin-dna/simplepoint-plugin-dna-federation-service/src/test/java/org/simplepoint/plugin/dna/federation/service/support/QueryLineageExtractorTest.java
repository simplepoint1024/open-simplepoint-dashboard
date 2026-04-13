package org.simplepoint.plugin.dna.federation.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryLineageExtractorTest {

  @Test
  void extractsSingleTableScan() {
    String planText = """
        EnumerableCalc(expr#0=[{inputs}])
          JdbcTableScan(table=[[mysql_ds, public, users]])
        """;
    List<QueryLineageExtractor.TableReference> refs =
        QueryLineageExtractor.extractSourceTables(planText);
    assertThat(refs).hasSize(1);
    assertThat(refs.get(0).datasourceCode()).isEqualTo("mysql_ds");
    assertThat(refs.get(0).schemaName()).isEqualTo("public");
    assertThat(refs.get(0).tableName()).isEqualTo("users");
  }

  @Test
  void extractsMultipleTableScans() {
    String planText = """
        EnumerableHashJoin(condition=[=($0, $3)])
          JdbcTableScan(table=[[ds1, schema_a, orders]])
          JdbcTableScan(table=[[ds2, schema_b, products]])
        """;
    List<QueryLineageExtractor.TableReference> refs =
        QueryLineageExtractor.extractSourceTables(planText);
    assertThat(refs).hasSize(2);
    assertThat(refs.get(0)).isEqualTo(
        new QueryLineageExtractor.TableReference("ds1", "schema_a", "orders"));
    assertThat(refs.get(1)).isEqualTo(
        new QueryLineageExtractor.TableReference("ds2", "schema_b", "products"));
  }

  @Test
  void deduplicatesSameTableReference() {
    String planText = """
        EnumerableCalc(expr#0=[{inputs}])
          JdbcTableScan(table=[[ds1, public, users]])
          JdbcTableScan(table=[[ds1, public, users]])
        """;
    List<QueryLineageExtractor.TableReference> refs =
        QueryLineageExtractor.extractSourceTables(planText);
    assertThat(refs).hasSize(1);
  }

  @Test
  void handlesNullAndEmptyPlanText() {
    assertThat(QueryLineageExtractor.extractSourceTables(null)).isEmpty();
    assertThat(QueryLineageExtractor.extractSourceTables("")).isEmpty();
    assertThat(QueryLineageExtractor.extractSourceTables("  ")).isEmpty();
  }

  @Test
  void handlesPlanWithNoTableScans() {
    String planText = "EnumerableValues(tuples=[[{ 1, 'a' }]])";
    assertThat(QueryLineageExtractor.extractSourceTables(planText)).isEmpty();
  }

  @Test
  void handlesTwoSegmentReference() {
    String planText = "JdbcTableScan(table=[[ds1, users]])";
    List<QueryLineageExtractor.TableReference> refs =
        QueryLineageExtractor.extractSourceTables(planText);
    assertThat(refs).hasSize(1);
    assertThat(refs.get(0).datasourceCode()).isEqualTo("ds1");
    assertThat(refs.get(0).schemaName()).isNull();
    assertThat(refs.get(0).tableName()).isEqualTo("users");
  }
}
