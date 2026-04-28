package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.DataQualityRule;
import org.simplepoint.plugin.dna.federation.api.repository.DataQualityRuleRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class DataQualityRuleServiceImplTest {

  @Mock
  private DataQualityRuleRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private FederationSqlConsoleService sqlConsoleService;

  private DataQualityRuleServiceImpl service() {
    return new DataQualityRuleServiceImpl(repository, detailsProviderService, dataSourceService, sqlConsoleService);
  }

  // ---- findActiveById ----

  @Test
  void findActiveByIdReturnsDecoratedRule() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));

    Optional<DataQualityRule> result = service().findActiveById("r1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isEqualTo("MySQL");
  }

  @Test
  void findActiveByIdReturnsEmptyWhenNotFound() {
    when(repository.findActiveById("missing")).thenReturn(Optional.empty());

    assertThat(service().findActiveById("missing")).isEmpty();
  }

  // ---- findActiveByCode ----

  @Test
  void findActiveByCodeTrimsAndDelegates() {
    DataQualityRule rule = validCustomSqlRule();
    when(repository.findActiveByCode("RULE_001")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));

    Optional<DataQualityRule> result = service().findActiveByCode("  RULE_001  ");

    assertThat(result).isPresent();
  }

  @Test
  void findActiveByCodeReturnsEmptyForNull() {
    when(repository.findActiveByCode(null)).thenReturn(Optional.empty());

    assertThat(service().findActiveByCode(null)).isEmpty();
  }

  // ---- limit ----

  @Test
  void limitDecoratesPageContent() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setCatalogId("catalog-1");
    Page<DataQualityRule> page = new PageImpl<>(List.of(rule), PageRequest.of(0, 10), 1);
    when(repository.limit(any(), any())).thenReturn((Page) page);
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "PG")));

    Page<DataQualityRule> result = service().limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getCatalogName()).isEqualTo("PG");
  }

  @Test
  void limitHandlesNullAttributes() {
    Page<DataQualityRule> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    when(repository.limit(any(), any())).thenReturn((Page) empty);

    Page<DataQualityRule> result = service().limit(null, PageRequest.of(0, 10));

    assertThat(result.getContent()).isEmpty();
  }

  // ---- create ----

  @Test
  void createSavesValidCustomSqlRule() {
    DataQualityRule rule = validCustomSqlRule();
    when(repository.findActiveByCode("RULE_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(repository.save(rule)).thenReturn(rule);

    DataQualityRule result = service().create(rule);

    assertThat(result).isNotNull();
    verify(repository).save(rule);
  }

  @Test
  void createAppliesEnabledDefault() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setEnabled(null);
    when(repository.findActiveByCode("RULE_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(repository.save(rule)).thenReturn(rule);

    service().create(rule);

    assertThat(rule.getEnabled()).isTrue();
  }

  @Test
  void createRejectsNullName() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setName(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(rule));
  }

  @Test
  void createRejectsNullCode() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setCode(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(rule));
  }

  @Test
  void createRejectsNullCatalogId() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setCatalogId(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(rule));
  }

  @Test
  void createRejectsNullRuleType() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setRuleType(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(rule));
  }

  @Test
  void createRejectsNullTargetTable() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setTargetTable(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(rule));
  }

  @Test
  void createRejectsNullSeverity() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setSeverity(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(rule));
  }

  @Test
  void createRejectsInvalidRuleType() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setRuleType("INVALID");

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(rule));
    assertThat(ex.getMessage()).contains("规则类型必须为");
  }

  @Test
  void createRejectsInvalidSeverity() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setSeverity("SUPER_CRITICAL");

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(rule));
    assertThat(ex.getMessage()).contains("严重级别必须为");
  }

  @Test
  void createRejectsCustomSqlWithoutCheckSql() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setCheckSql(null);

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(rule));
    assertThat(ex.getMessage()).contains("自定义 SQL 规则必须提供检查 SQL");
  }

  @Test
  void createRejectsColumnRequiredTypeWithoutColumn() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setRuleType("NOT_NULL");
    rule.setTargetColumn(null);

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(rule));
    assertThat(ex.getMessage()).contains("目标列");
  }

  @Test
  void createRejectsDuplicateCode() {
    DataQualityRule rule = validCustomSqlRule();
    DataQualityRule existing = validCustomSqlRule();
    existing.setId("other-id");
    when(repository.findActiveByCode("RULE_001")).thenReturn(Optional.of(existing));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(rule));
    assertThat(ex.getMessage()).contains("规则编码已存在");
  }

  @Test
  void createRejectsDisabledDataSource() {
    DataQualityRule rule = validCustomSqlRule();
    JdbcDataSourceDefinition ds = enabledDs("catalog-1", "MySQL");
    ds.setEnabled(false);
    when(repository.findActiveByCode("RULE_001")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(ds));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(rule));
    assertThat(ex.getMessage()).contains("数据源不存在或未启用");
  }

  // ---- modifyById ----

  @Test
  void modifyByIdThrowsWhenRuleNotFound() {
    DataQualityRule entity = validCustomSqlRule();
    entity.setId("r-missing");
    when(repository.findActiveById("r-missing")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().modifyById(entity));
    assertThat(ex.getMessage()).contains("质量规则不存在");
  }

  @Test
  void modifyByIdUpdatesRule() {
    DataQualityRule current = validCustomSqlRule();
    current.setId("r1");
    DataQualityRule entity = validCustomSqlRule();
    entity.setId("r1");

    when(repository.findActiveById("r1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("RULE_001")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    DataQualityRule result = service().modifyById(entity);

    assertThat(result).isNotNull();
  }

  @Test
  void modifyByIdInheritsEnabledFromCurrent() {
    DataQualityRule current = validCustomSqlRule();
    current.setId("r1");
    current.setEnabled(false);
    DataQualityRule entity = validCustomSqlRule();
    entity.setId("r1");
    entity.setEnabled(null);

    when(repository.findActiveById("r1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("RULE_001")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    service().modifyById(entity);

    assertThat(entity.getEnabled()).isFalse();
  }

  // ---- executeCheck ----

  @Test
  void executeCheckThrowsWhenRuleNotFound() {
    when(repository.findActiveById("r-missing")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().executeCheck("r-missing"));
    assertThat(ex.getMessage()).contains("质量规则不存在");
  }

  @Test
  void executeCheckNotNullRulePassesWhenZeroViolations() {
    DataQualityRule rule = validNotNullRule();
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    FederationQueryModels.SqlQueryResult queryResult = queryResult("0");
    when(sqlConsoleService.execute(any())).thenReturn(queryResult);
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("PASSED");
  }

  @Test
  void executeCheckNotNullRuleFailsWhenViolationsFound() {
    DataQualityRule rule = validNotNullRule();
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    FederationQueryModels.SqlQueryResult queryResult = queryResult("5");
    when(sqlConsoleService.execute(any())).thenReturn(queryResult);
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("FAILED");
  }

  @Test
  void executeCheckCustomSqlRulePassesWhenResultMatchesExpected() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setId("r1");
    rule.setExpectedValue("42");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(sqlConsoleService.execute(any())).thenReturn(queryResult("42"));
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("PASSED");
  }

  @Test
  void executeCheckSetsErrorStatusWhenDataSourceMissing() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.empty());
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("ERROR");
  }

  @Test
  void executeCheckSetsErrorWhenSqlConsoleThrows() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(sqlConsoleService.execute(any())).thenThrow(new RuntimeException("connection refused"));
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("ERROR");
    assertThat(result.getLastRunMessage()).contains("connection refused");
  }

  @Test
  void executeCheckRowCountRulePassesWhenCountInRange() {
    DataQualityRule rule = rowCountRule("10,100");
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(sqlConsoleService.execute(any())).thenReturn(queryResult("50"));
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("PASSED");
  }

  @Test
  void executeCheckRowCountRuleFailsWhenCountBelowRange() {
    DataQualityRule rule = rowCountRule("10,100");
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(sqlConsoleService.execute(any())).thenReturn(queryResult("5"));
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("FAILED");
  }

  @Test
  void executeCheckReturnsErrorWhenQueryResultEmpty() {
    DataQualityRule rule = validCustomSqlRule();
    rule.setId("r1");
    when(repository.findActiveById("r1")).thenReturn(Optional.of(rule));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(sqlConsoleService.execute(any())).thenReturn(emptyQueryResult());
    when(repository.findById("r1")).thenReturn(Optional.empty());
    when(repository.updateById(rule)).thenReturn(rule);

    DataQualityRule result = service().executeCheck("r1");

    assertThat(result.getLastRunStatus()).isEqualTo("FAILED");
  }

  // ---- helpers ----

  private DataQualityRule validCustomSqlRule() {
    DataQualityRule rule = new DataQualityRule();
    rule.setName("Custom Check");
    rule.setCode("RULE_001");
    rule.setCatalogId("catalog-1");
    rule.setRuleType("CUSTOM_SQL");
    rule.setTargetTable("orders");
    rule.setSeverity("ERROR");
    rule.setCheckSql("SELECT COUNT(*) FROM orders");
    return rule;
  }

  private DataQualityRule validNotNullRule() {
    DataQualityRule rule = new DataQualityRule();
    rule.setName("Not Null Check");
    rule.setCode("RULE_NN");
    rule.setCatalogId("catalog-1");
    rule.setRuleType("NOT_NULL");
    rule.setTargetTable("orders");
    rule.setTargetColumn("order_id");
    rule.setSeverity("ERROR");
    return rule;
  }

  private DataQualityRule rowCountRule(final String expectedValue) {
    DataQualityRule rule = new DataQualityRule();
    rule.setName("Row Count");
    rule.setCode("RULE_RC");
    rule.setCatalogId("catalog-1");
    rule.setRuleType("ROW_COUNT");
    rule.setTargetTable("orders");
    rule.setSeverity("WARNING");
    rule.setExpectedValue(expectedValue);
    return rule;
  }

  private static JdbcDataSourceDefinition enabledDs(final String id, final String name) {
    JdbcDataSourceDefinition ds = new JdbcDataSourceDefinition();
    ds.setId(id);
    ds.setName(name);
    ds.setCode(name);
    ds.setEnabled(true);
    return ds;
  }

  private static FederationQueryModels.SqlQueryResult queryResult(final String value) {
    List<List<Object>> rows = List.of(List.of(value));
    return new FederationQueryModels.SqlQueryResult(
        null, null, 1000, 30000, false, false,
        List.of(), List.of(new FederationQueryModels.SqlColumn("cnt", "BIGINT")),
        rows, false, 1L, 10L, null, null, null
    );
  }

  private static FederationQueryModels.SqlQueryResult emptyQueryResult() {
    return new FederationQueryModels.SqlQueryResult(
        null, null, 1000, 30000, false, false,
        List.of(), List.of(), List.of(), false, 0L, 10L, null, null, null
    );
  }
}
