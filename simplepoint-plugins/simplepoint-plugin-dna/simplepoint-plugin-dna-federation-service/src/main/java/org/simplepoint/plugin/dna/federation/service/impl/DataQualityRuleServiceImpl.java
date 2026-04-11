package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireEntityId;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.DataQualityRule;
import org.simplepoint.plugin.dna.federation.api.repository.DataQualityRuleRepository;
import org.simplepoint.plugin.dna.federation.api.service.DataQualityRuleService;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Data quality rule service implementation. Supports CRUD for quality rules
 * and executes SQL-based quality checks against datasources.
 */
@Service
public class DataQualityRuleServiceImpl
    extends BaseServiceImpl<DataQualityRuleRepository, DataQualityRule, String>
    implements DataQualityRuleService {

  private static final Set<String> VALID_RULE_TYPES = Set.of(
      "NOT_NULL", "UNIQUE", "RANGE", "REGEX", "ROW_COUNT", "CUSTOM_SQL"
  );

  private static final Set<String> VALID_SEVERITIES = Set.of("INFO", "WARNING", "ERROR", "CRITICAL");

  private final DataQualityRuleRepository repository;

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final FederationSqlConsoleService sqlConsoleService;

  /**
   * Creates a data quality rule service.
   *
   * @param repository             rule repository
   * @param detailsProviderService details provider service
   * @param dataSourceService      datasource service
   * @param sqlConsoleService      SQL console service for executing checks
   */
  public DataQualityRuleServiceImpl(
      final DataQualityRuleRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDataSourceDefinitionService dataSourceService,
      final FederationSqlConsoleService sqlConsoleService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.dataSourceService = dataSourceService;
    this.sqlConsoleService = sqlConsoleService;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<DataQualityRule> findActiveById(final String id) {
    return repository.findActiveById(id).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public Optional<DataQualityRule> findActiveByCode(final String code) {
    return repository.findActiveByCode(trimToNull(code)).map(this::decorate);
  }

  /** {@inheritDoc} */
  @Override
  public long countActive() {
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("deletedAt", "is:null");
    attrs.put("enabled", "true");
    return super.limit(attrs, Pageable.ofSize(1)).getTotalElements();
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataQualityRule> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "code");
    normalizeLikeQuery(normalized, "targetTable");
    normalizeLikeQuery(normalized, "targetColumn");
    Page<S> page = super.limit(normalized, pageable);
    decorate(page.getContent());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataQualityRule> S create(final S entity) {
    normalizeAndValidate(entity, null);
    applyDefaults(entity);
    S saved = super.create(entity);
    decorate(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataQualityRule> DataQualityRule modifyById(final S entity) {
    DataQualityRule current = repository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    DataQualityRule updated = (DataQualityRule) super.modifyById(entity);
    decorate(updated);
    return updated;
  }

  /** {@inheritDoc} */
  @Override
  public DataQualityRule executeCheck(final String ruleId) {
    DataQualityRule rule = repository.findActiveById(ruleId)
        .orElseThrow(() -> new IllegalArgumentException("质量规则不存在: " + ruleId));

    String sql = buildCheckSql(rule);
    if (sql == null || sql.isBlank()) {
      throw new IllegalArgumentException("质量规则未配置检查 SQL");
    }

    try {
      // Resolve catalog code from catalog id
      String catalogCode = dataSourceService.findActiveById(rule.getCatalogId())
          .map(JdbcDataSourceDefinition::getCode)
          .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + rule.getCatalogId()));

      FederationQueryModels.SqlConsoleRequest request =
          new FederationQueryModels.SqlConsoleRequest(catalogCode, sql);

      FederationQueryModels.SqlQueryResult result = sqlConsoleService.execute(request);

      boolean passed = evaluateResult(rule, result);
      rule.setLastRunStatus(passed ? "PASSED" : "FAILED");
      rule.setLastRunMessage(passed
          ? "检查通过"
          : "检查未通过 - 实际结果不符合预期");
    } catch (Exception ex) {
      rule.setLastRunStatus("ERROR");
      String msg = ex.getMessage();
      rule.setLastRunMessage(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
    }

    rule.setLastRunAt(Instant.now());
    return (DataQualityRule) super.modifyById(rule);
  }

  private String buildCheckSql(final DataQualityRule rule) {
    if ("CUSTOM_SQL".equals(rule.getRuleType())) {
      return rule.getCheckSql();
    }
    String table = rule.getTargetTable();
    String column = rule.getTargetColumn();
    return switch (rule.getRuleType()) {
      case "NOT_NULL" -> "SELECT COUNT(*) AS cnt FROM " + table + " WHERE " + column + " IS NULL";
      case "UNIQUE" -> "SELECT COUNT(*) - COUNT(DISTINCT " + column + ") AS cnt FROM " + table;
      case "ROW_COUNT" -> "SELECT COUNT(*) AS cnt FROM " + table;
      case "RANGE" -> {
        // expectedValue format: "min,max"
        String expected = rule.getExpectedValue();
        if (expected != null && expected.contains(",")) {
          String[] parts = expected.split(",", 2);
          yield "SELECT COUNT(*) AS cnt FROM " + table
              + " WHERE " + column + " < " + parts[0].trim()
              + " OR " + column + " > " + parts[1].trim();
        }
        yield "SELECT COUNT(*) AS cnt FROM " + table;
      }
      case "REGEX" -> "SELECT COUNT(*) AS cnt FROM " + table
          + " WHERE " + column + " NOT REGEXP '" + (rule.getExpectedValue() != null
          ? rule.getExpectedValue().replace("'", "''") : "") + "'";
      default -> rule.getCheckSql();
    };
  }

  private boolean evaluateResult(final DataQualityRule rule, final FederationQueryModels.SqlQueryResult result) {
    if (result == null || result.rows() == null || result.rows().isEmpty()) {
      return false;
    }
    List<Object> firstRow = result.rows().get(0);
    if (firstRow == null || firstRow.isEmpty()) {
      return false;
    }
    Object firstValue = firstRow.get(0);
    if (firstValue == null) {
      return false;
    }
    String valueStr = firstValue.toString().trim();

    return switch (rule.getRuleType()) {
      case "NOT_NULL", "UNIQUE", "RANGE", "REGEX" ->
          // These count violations, so 0 means pass
          "0".equals(valueStr);
      case "ROW_COUNT" -> {
        // expectedValue is the expected count or range "min,max"
        String expected = rule.getExpectedValue();
        if (expected == null) {
          yield Long.parseLong(valueStr) > 0;
        }
        if (expected.contains(",")) {
          String[] parts = expected.split(",", 2);
          long actual = Long.parseLong(valueStr);
          yield actual >= Long.parseLong(parts[0].trim())
              && actual <= Long.parseLong(parts[1].trim());
        }
        yield valueStr.equals(expected.trim());
      }
      case "CUSTOM_SQL" -> {
        String expected = rule.getExpectedValue();
        yield expected == null || expected.isBlank() || valueStr.equals(expected.trim());
      }
      default -> true;
    };
  }

  private void normalizeAndValidate(final DataQualityRule entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("质量规则不能为空");
    }
    entity.setName(requireValue(entity.getName(), "规则名称不能为空"));
    entity.setCode(requireValue(entity.getCode(), "规则编码不能为空"));
    entity.setCatalogId(requireValue(entity.getCatalogId(), "数据源不能为空"));
    entity.setRuleType(requireValue(entity.getRuleType(), "规则类型不能为空"));
    entity.setTargetTable(requireValue(entity.getTargetTable(), "目标表不能为空"));
    entity.setSeverity(requireValue(entity.getSeverity(), "严重级别不能为空"));
    entity.setDescription(trimToNull(entity.getDescription()));
    entity.setTargetColumn(trimToNull(entity.getTargetColumn()));
    entity.setCheckSql(trimToNull(entity.getCheckSql()));
    entity.setExpectedValue(trimToNull(entity.getExpectedValue()));

    if (!VALID_RULE_TYPES.contains(entity.getRuleType())) {
      throw new IllegalArgumentException(
          "规则类型必须为 " + String.join(", ", VALID_RULE_TYPES));
    }
    if (!VALID_SEVERITIES.contains(entity.getSeverity())) {
      throw new IllegalArgumentException(
          "严重级别必须为 " + String.join(", ", VALID_SEVERITIES));
    }
    if ("CUSTOM_SQL".equals(entity.getRuleType())
        && (entity.getCheckSql() == null || entity.getCheckSql().isBlank())) {
      throw new IllegalArgumentException("自定义 SQL 规则必须提供检查 SQL");
    }

    repository.findActiveByCode(entity.getCode())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("规则编码已存在: " + entity.getCode());
        });
    dataSourceService.findActiveById(entity.getCatalogId())
        .filter(ds -> Boolean.TRUE.equals(ds.getEnabled()))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在或未启用: " + entity.getCatalogId()));
  }

  private void applyDefaults(final DataQualityRule entity) {
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
  }

  private DataQualityRule decorate(final DataQualityRule item) {
    if (item == null) {
      return null;
    }
    dataSourceService.findActiveById(item.getCatalogId()).ifPresentOrElse(
        ds -> item.setCatalogName(ds.getName()),
        () -> item.setCatalogName(null)
    );
    return item;
  }

  private <S extends DataQualityRule> void decorate(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> catalogIds = items.stream()
        .map(DataQualityRule::getCatalogId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, JdbcDataSourceDefinition> dataSourcesById = catalogIds.stream()
        .map(dataSourceService::findActiveById)
        .flatMap(Optional::stream)
        .collect(Collectors.toMap(JdbcDataSourceDefinition::getId, ds -> ds, (l, r) -> l));
    items.forEach(item -> {
      JdbcDataSourceDefinition ds = dataSourcesById.get(item.getCatalogId());
      item.setCatalogName(ds != null ? ds.getName() : null);
    });
  }
}
