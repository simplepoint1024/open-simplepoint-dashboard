package org.simplepoint.plugin.dna.federation.api.vo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.federation.api.pojo.dto.FederationJdbcUserDataSourceAssignDto;

class FederationModelsTest {

  // ---- FederationJdbcDriverModels records ----

  @Test
  void driverRequest_fieldsAccessible() {
    var req = new FederationJdbcDriverModels.DriverRequest("user@test.com", "secret", "cat1", "t1", "ctx1");
    assertThat(req.loginSubject()).isEqualTo("user@test.com");
    assertThat(req.password()).isEqualTo("secret");
    assertThat(req.catalogCode()).isEqualTo("cat1");
    assertThat(req.tenantId()).isEqualTo("t1");
    assertThat(req.contextId()).isEqualTo("ctx1");
  }

  @Test
  void queryRequest_twoArgConstructor() {
    var req = new FederationJdbcDriverModels.QueryRequest("SELECT 1", "myschema");
    assertThat(req.sql()).isEqualTo("SELECT 1");
    assertThat(req.defaultSchema()).isEqualTo("myschema");
    assertThat(req.catalogCode()).isNull();
    assertThat(req.parameters()).isNull();
    assertThat(req.maxRows()).isNull();
  }

  @Test
  void queryRequest_threeArgConstructor() {
    var req = new FederationJdbcDriverModels.QueryRequest("SELECT 1", "myschema", "cat1");
    assertThat(req.catalogCode()).isEqualTo("cat1");
  }

  @Test
  void tabularResult_nullInputsNormalized() {
    var result = new FederationJdbcDriverModels.TabularResult(null, null);
    assertThat(result.columns()).isEmpty();
    assertThat(result.rows()).isEmpty();
  }

  @Test
  void tabularResult_rowsImmutable() {
    var col = new FederationJdbcDriverModels.JdbcColumn("id", "VARCHAR", java.sql.Types.VARCHAR);
    var rows = new java.util.ArrayList<List<Object>>();
    rows.add(List.of("val1"));
    var result = new FederationJdbcDriverModels.TabularResult(List.of(col), rows);
    assertThat(result.rows()).hasSize(1);
    assertThat(result.columns().get(0).name()).isEqualTo("id");
  }

  @Test
  void pingResult_fieldsAccessible() {
    var ping = new FederationJdbcDriverModels.PingResult(
        "cat1", "t1", "ctx1", "u1", "user@test.com", "MySQL", "8.0", "public");
    assertThat(ping.catalogCode()).isEqualTo("cat1");
    assertThat(ping.databaseProductName()).isEqualTo("MySQL");
  }

  // ---- FederationQueryModels records ----

  @Test
  void sqlConsoleRequest_twoArgConstructor() {
    var req = new FederationQueryModels.SqlConsoleRequest("cat1", "SELECT 1");
    assertThat(req.catalogCode()).isEqualTo("cat1");
    assertThat(req.sql()).isEqualTo("SELECT 1");
    assertThat(req.defaultSchema()).isNull();
    assertThat(req.parameters()).isNull();
    assertThat(req.maxRows()).isNull();
  }

  @Test
  void sqlExplainResult_nullsNormalized() {
    var explain = new FederationQueryModels.SqlExplainResult(
        "cat1", null, 100, 5000, true, false, null, null, null, null);
    assertThat(explain.dataSources()).isEmpty();
    assertThat(explain.planText()).isEmpty();
    assertThat(explain.pushedSqls()).isEmpty();
    assertThat(explain.pushdownSummary()).isNull();
  }

  @Test
  void sqlExecuteResult_factoryMethods() {
    var col = new FederationQueryModels.SqlColumn("id", "VARCHAR");
    var queryResult = new FederationQueryModels.SqlQueryResult(
        "cat1", null, 100, 5000, true, false,
        null, List.of(col), null, false, 1L, 10L, null, null, null);
    assertThat(queryResult.columns()).hasSize(1);
    assertThat(queryResult.planText()).isEmpty();

    var exec = FederationQueryModels.SqlExecuteResult.query(queryResult);
    assertThat(exec.type()).isEqualTo("QUERY");
    assertThat(exec.queryResult()).isNotNull();

    var updateResult = new FederationQueryModels.SqlUpdateResult("cat1", "ds1", 3L, 100L, "UPDATE t SET x=1");
    assertThat(FederationQueryModels.SqlExecuteResult.dml(updateResult).type()).isEqualTo("DML");
    assertThat(FederationQueryModels.SqlExecuteResult.ddl(updateResult).type()).isEqualTo("DDL");
    assertThat(FederationQueryModels.SqlExecuteResult.flushCache("ok").type()).isEqualTo("FLUSH_CACHE");
  }

  // ---- FederationJdbcUserDataSourceItemVo ----

  @Test
  void jdbcUserDataSourceItemVo_equality() {
    var vo1 = new FederationJdbcUserDataSourceItemVo("id1", "code1", "Name", "MySQL");
    var vo2 = new FederationJdbcUserDataSourceItemVo("id1", "code1", "Name", "MySQL");
    assertThat(vo1).isEqualTo(vo2);
    assertThat(vo1.id()).isEqualTo("id1");
    assertThat(vo1.databaseProductName()).isEqualTo("MySQL");
  }

  // ---- FederationJdbcUserDataSourceAssignDto ----

  @Test
  void assignDto_setterGetter() {
    var dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("u1");
    dto.setDataSourceIds(java.util.Set.of("ds1", "ds2"));
    assertThat(dto.getUserId()).isEqualTo("u1");
    assertThat(dto.getDataSourceIds()).containsExactlyInAnyOrder("ds1", "ds2");
  }
}
