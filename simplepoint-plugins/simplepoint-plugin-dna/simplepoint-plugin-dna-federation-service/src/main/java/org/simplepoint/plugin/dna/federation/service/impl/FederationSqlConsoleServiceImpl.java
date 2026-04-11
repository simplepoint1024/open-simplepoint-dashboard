package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlMerge;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.simplepoint.data.calcite.core.query.CalciteQueryAnalysis;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler;
import org.simplepoint.plugin.dna.federation.service.support.FederationSqlIdentifierNormalizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Federation SQL console service implementation.
 */
@Service
public class FederationSqlConsoleServiceImpl implements FederationSqlConsoleService {

  private static final int SQL_TEXT_MAX_LENGTH = 12_000;

  private static final int AUDIT_MESSAGE_MAX_LENGTH = 4_000;

  private static final Pattern JDBC_TABLE_SCAN_PATTERN = Pattern.compile("JdbcTableScan\\(table=\\[\\[([^\\]]+)]]");

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final FederationQueryPolicyRepository policyRepository;

  private final FederationQueryAuditService auditService;

  private final FederationCalciteCatalogAssembler catalogAssembler;

  private final CalciteQueryEngine queryEngine;

  /**
   * Creates a SQL console service implementation.
   *
   * @param dataSourceService datasource service
   * @param policyRepository query policy repository
   * @param auditService query audit service
   * @param catalogAssembler Calcite catalog assembler
   * @param queryEngine Calcite query engine
   */
  public FederationSqlConsoleServiceImpl(
      final JdbcDataSourceDefinitionService dataSourceService,
      final FederationQueryPolicyRepository policyRepository,
      final FederationQueryAuditService auditService,
      final FederationCalciteCatalogAssembler catalogAssembler,
      final CalciteQueryEngine queryEngine
  ) {
    this.dataSourceService = dataSourceService;
    this.policyRepository = policyRepository;
    this.auditService = auditService;
    this.catalogAssembler = catalogAssembler;
    this.queryEngine = queryEngine;
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlExplainResult explain(final FederationQueryModels.SqlConsoleRequest request) {
    try (PreparedExecution prepared = prepare(null, request)) {
      return new FederationQueryModels.SqlExplainResult(
          prepared.catalogCode(),
          prepared.policy().code(),
          prepared.policy().maxRows(),
          prepared.policy().timeoutMs(),
          prepared.policy().allowCrossSourceJoin(),
          prepared.crossSourceJoin(),
          prepared.dataSources(),
          prepared.analysis().planText(),
          prepared.analysis().pushedSqls(),
          prepared.pushdownSummary()
      );
    }
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlQueryResult execute(final FederationQueryModels.SqlConsoleRequest request) {
    return execute((String) null, request);
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlQueryResult execute(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    PreparedExecution prepared = null;
    long startedAt = System.nanoTime();
    try {
      prepared = prepare(dataSourceId, request);
      CalciteQueryResult result = queryEngine.execute(prepared.queryRequest(), prepared.assembly().schemaConfigurer());
      List<String> resultSources = resolveResponseDataSources(prepared, result.analysis());
      String pushdownSummary = buildPushdownSummary(result.analysis(), resultSources);
      FederationQueryModels.SqlQueryResult response = new FederationQueryModels.SqlQueryResult(
          prepared.catalogCode(),
          prepared.policy().code(),
          prepared.policy().maxRows(),
          prepared.policy().timeoutMs(),
          prepared.policy().allowCrossSourceJoin(),
          resultSources.size() > 1,
          resultSources,
          result.columns().stream()
              .map(column -> new FederationQueryModels.SqlColumn(column.name(), column.typeName(), column.jdbcType()))
              .toList(),
          result.rows(),
          result.truncated(),
          result.returnedRows(),
          result.executionTimeMs(),
          result.analysis().planText(),
          result.analysis().pushedSqls(),
          pushdownSummary
      );
      persistAudit(
          prepared.catalogCode(),
          prepared.queryRequest().sql(),
          "SUCCESS",
          result.executionTimeMs(),
          result.returnedRows(),
          pushdownSummary,
          null
      );
      return response;
    } catch (IllegalArgumentException ex) {
      throw auditFailureAndReturn(ex, request, prepared, startedAt, "REJECTED");
    } catch (PolicyViolationException ex) {
      throw auditFailureAndReturn(ex, request, prepared, startedAt, "REJECTED");
    } catch (IllegalStateException ex) {
      throw auditFailureAndReturn(ex, request, prepared, startedAt, "FAILED");
    } finally {
      closePreparedExecution(prepared);
    }
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlUpdateResult executeUpdate(
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    return executeUpdate((String) null, request);
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlUpdateResult executeUpdate(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    String catalogCode = requireValue(request == null ? null : request.catalogCode(), "数据源编码不能为空");
    String sql = requireValue(request == null ? null : request.sql(), "SQL 不能为空");
    if (sql.length() > SQL_TEXT_MAX_LENGTH) {
      throw new IllegalArgumentException("SQL 长度不能超过 " + SQL_TEXT_MAX_LENGTH + " 个字符");
    }
    String normalizedSql = normalizeQualifiedIdentifiers(sql);
    JdbcDataSourceDefinition resolvedDataSource = resolveDataSource(dataSourceId, catalogCode);
    TableReferenceSummary references = collectTableReferences(normalizedSql);
    DmlTarget dmlTarget = resolveDmlTarget(resolvedDataSource, references);
    SimpleDataSource simpleDataSource = dataSourceService.requireSimpleDataSource(
        requireValue(dmlTarget.dataSource().getId(), "数据源ID不能为空")
    );
    String pushedSql = rewriteDmlForPhysicalDatabase(normalizedSql, dmlTarget.dataSource().getCode());
    long startedAt = System.nanoTime();
    try (Connection connection = simpleDataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(pushedSql)) {
      int affectedRows = statement.executeUpdate();
      long elapsed = toElapsedMs(startedAt);
      persistAudit(
          catalogCode,
          normalizedSql,
          "SUCCESS",
          elapsed,
          (long) affectedRows,
          "DML 直接下推到物理数据源: " + dmlTarget.dataSource().getCode(),
          null
      );
      return new FederationQueryModels.SqlUpdateResult(
          catalogCode,
          dmlTarget.dataSource().getCode(),
          affectedRows,
          elapsed,
          pushedSql
      );
    } catch (SQLException ex) {
      long elapsed = toElapsedMs(startedAt);
      persistAudit(
          catalogCode,
          normalizedSql,
          "FAILED",
          elapsed,
          null,
          "DML 执行失败 - 目标数据源: " + dmlTarget.dataSource().getCode(),
          resolveMessage(ex)
      );
      throw new IllegalStateException("DML 执行失败: " + ex.getMessage(), ex);
    }
  }

  /**
   * Resolves the single physical datasource that is the DML target.
   * DML must target exactly one physical datasource — cross-source DML is forbidden.
   */
  private DmlTarget resolveDmlTarget(
      final JdbcDataSourceDefinition selectedDataSource,
      final TableReferenceSummary references
  ) {
    if (!references.hasTableReferences()) {
      return new DmlTarget(selectedDataSource);
    }
    LinkedHashMap<String, JdbcDataSourceDefinition> resolved = new LinkedHashMap<>();
    List<JdbcDataSourceDefinition> enabledDataSources = dataSourceService.listEnabledDefinitions();
    Map<String, JdbcDataSourceDefinition> dataSourcesByCode = new LinkedHashMap<>();
    enabledDataSources.forEach(definition -> {
      String code = trimToNull(definition == null ? null : definition.getCode());
      if (code != null) {
        dataSourcesByCode.putIfAbsent(code.toLowerCase(Locale.ROOT), definition);
      }
    });
    boolean usesSelectedDataSource = false;
    for (List<String> identifier : references.identifiers()) {
      if (identifier.size() < 2) {
        usesSelectedDataSource = true;
        continue;
      }
      JdbcDataSourceDefinition explicitDataSource = dataSourcesByCode.get(identifier.get(0).toLowerCase(Locale.ROOT));
      if (explicitDataSource != null) {
        resolved.putIfAbsent(explicitDataSource.getId(), explicitDataSource);
      } else {
        usesSelectedDataSource = true;
      }
    }
    if (usesSelectedDataSource) {
      resolved.putIfAbsent(requireValue(selectedDataSource.getId(), "数据源ID不能为空"), selectedDataSource);
    }
    if (resolved.size() > 1) {
      throw new IllegalArgumentException("DML 语句不允许跨数据源操作，检测到多个目标数据源: "
          + String.join(", ", resolved.values().stream().map(JdbcDataSourceDefinition::getCode).toList()));
    }
    if (resolved.isEmpty()) {
      return new DmlTarget(selectedDataSource);
    }
    return new DmlTarget(resolved.values().iterator().next());
  }

  /**
   * Rewrites federation DML SQL for the physical database by stripping the datasource code prefix.
   * E.g., {@code INSERT INTO mysql_ds.users ...} becomes {@code INSERT INTO users ...}
   */
  private static String rewriteDmlForPhysicalDatabase(
      final String sql,
      final String dataSourceCode
  ) {
    String normalizedSql = trimToNull(sql);
    if (normalizedSql == null) {
      return sql;
    }
    try {
      SqlParser parser = SqlParser.create(normalizedSql);
      SqlNodeList statements = parser.parseStmtList();
      if (statements.size() != 1 || statements.get(0) == null) {
        return normalizedSql;
      }
      DmlTableRewriter rewriter = new DmlTableRewriter(normalizedSql, dataSourceCode);
      rewriter.rewrite(statements.get(0));
      return rewriter.apply();
    } catch (SqlParseException ex) {
      return normalizedSql;
    }
  }

  private record DmlTarget(JdbcDataSourceDefinition dataSource) {
  }

  private PreparedExecution prepare(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    String catalogCode = requireValue(request == null ? null : request.catalogCode(), "数据源编码不能为空");
    String sql = requireValue(request == null ? null : request.sql(), "SQL 不能为空");
    if (sql.length() > SQL_TEXT_MAX_LENGTH) {
      throw new IllegalArgumentException("SQL 长度不能超过 " + SQL_TEXT_MAX_LENGTH + " 个字符");
    }
    String normalizedSql = normalizeQualifiedIdentifiers(sql);
    JdbcDataSourceDefinition resolvedDataSource = resolveDataSource(dataSourceId, catalogCode);
    ResolvedPolicy policy = resolvePolicy(resolvedDataSource);
    List<JdbcDataSourceDefinition> queryDataSources = resolveQueryDataSources(resolvedDataSource, normalizedSql);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = catalogAssembler.assemble(
        resolvedDataSource.getCode(),
        queryDataSources
    );
    try {
      CalciteQueryRequest queryRequest = new CalciteQueryRequest(
          normalizedSql,
          resolveDefaultSchema(request == null ? null : request.defaultSchema(), resolvedDataSource.getCode(), queryDataSources),
          policy.maxRows(),
          policy.timeoutMs()
      );
      CalciteQueryAnalysis analysis = queryEngine.explain(queryRequest, assembly.schemaConfigurer());
      List<String> dataSources = extractUsedDataSources(analysis.planText(), assembly.physicalDataSourceCodes());
      boolean crossSourceJoin = dataSources.size() > 1;
      if (crossSourceJoin && !policy.allowCrossSourceJoin()) {
        throw new PolicyViolationException("当前查询策略禁止跨数据源 Join");
      }
      return new PreparedExecution(
          resolvedDataSource.getCode(),
          policy,
          assembly,
          queryRequest,
          analysis,
          dataSources,
          crossSourceJoin,
          buildPushdownSummary(analysis, dataSources)
      );
    } catch (RuntimeException ex) {
      assembly.close();
      throw ex;
    }
  }

  private JdbcDataSourceDefinition resolveDataSource(
      final String dataSourceId,
      final String catalogCode
  ) {
    if (trimToNull(dataSourceId) == null) {
      return resolveDataSource(catalogCode);
    }
    JdbcDataSourceDefinition dataSource = dataSourceService.findActiveById(dataSourceId)
        .orElseThrow(() -> new PolicyViolationException("数据源不存在: " + dataSourceId));
    if (!Objects.equals(trimToNull(dataSource.getCode()), catalogCode)) {
      throw new IllegalArgumentException("数据源编码与已解析数据源不一致: " + catalogCode);
    }
    if (!Boolean.TRUE.equals(dataSource.getEnabled())) {
      throw new PolicyViolationException("数据源已禁用: " + catalogCode);
    }
    return dataSource;
  }

  private JdbcDataSourceDefinition resolveDataSource(final String catalogCode) {
    JdbcDataSourceDefinition dataSource = dataSourceService.findActiveByCode(catalogCode)
        .orElseThrow(() -> new PolicyViolationException("数据源不存在: " + catalogCode));
    if (!Boolean.TRUE.equals(dataSource.getEnabled())) {
      throw new PolicyViolationException("数据源已禁用: " + catalogCode);
    }
    return dataSource;
  }

  private ResolvedPolicy resolvePolicy(final JdbcDataSourceDefinition dataSource) {
    FederationQueryPolicy policy = policyRepository.findAllActiveByCatalogId(requireValue(
            dataSource == null ? null : dataSource.getId(),
            "数据源ID不能为空"
        )).stream()
        .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
        .sorted(Comparator.comparing(
            FederationQueryPolicy::getUpdatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(
            FederationQueryPolicy::getCreatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(FederationQueryPolicy::getCode, Comparator.nullsLast(String::compareTo)))
        .findFirst()
        .orElseThrow(() -> new PolicyViolationException("数据源未配置已启用的查询策略: " + dataSource.getCode()));
    if (!Boolean.TRUE.equals(policy.getAllowSqlConsole())) {
      throw new PolicyViolationException("当前查询策略未开放 SQL 控制台: " + policy.getCode());
    }
    Integer maxRows = policy.getMaxRows();
    if (maxRows == null || maxRows < 1) {
      throw new PolicyViolationException("查询策略未配置有效的最大返回行数: " + policy.getCode());
    }
    Integer timeoutMs = policy.getTimeoutMs();
    if (timeoutMs == null || timeoutMs < 1) {
      throw new PolicyViolationException("查询策略未配置有效的超时时间: " + policy.getCode());
    }
    return new ResolvedPolicy(
        requireValue(policy.getCode(), "查询策略编码不能为空"),
        maxRows,
        timeoutMs,
        Boolean.TRUE.equals(policy.getAllowCrossSourceJoin())
    );
  }

  private List<JdbcDataSourceDefinition> resolveQueryDataSources(
      final JdbcDataSourceDefinition selectedDataSource,
      final String sql
  ) {
    TableReferenceSummary references = collectTableReferences(sql);
    if (!references.hasTableReferences()) {
      return List.of();
    }
    List<JdbcDataSourceDefinition> enabledDataSources = dataSourceService.listEnabledDefinitions();
    Map<String, JdbcDataSourceDefinition> dataSourcesByCode = new LinkedHashMap<>();
    enabledDataSources.forEach(definition -> {
      String code = trimToNull(definition == null ? null : definition.getCode());
      if (code != null) {
        dataSourcesByCode.putIfAbsent(code.toLowerCase(Locale.ROOT), definition);
      }
    });
    LinkedHashMap<String, JdbcDataSourceDefinition> resolved = new LinkedHashMap<>();
    boolean includeSelectedDataSource = false;
    for (List<String> identifier : references.identifiers()) {
      if (identifier.size() < 2) {
        includeSelectedDataSource = true;
        continue;
      }
      JdbcDataSourceDefinition explicitDataSource = dataSourcesByCode.get(identifier.get(0).toLowerCase(Locale.ROOT));
      if (explicitDataSource != null) {
        resolved.putIfAbsent(explicitDataSource.getId(), explicitDataSource);
      } else {
        includeSelectedDataSource = true;
      }
    }
    if (includeSelectedDataSource) {
      LinkedHashMap<String, JdbcDataSourceDefinition> ordered = new LinkedHashMap<>();
      ordered.put(requireValue(selectedDataSource.getId(), "数据源ID不能为空"), selectedDataSource);
      resolved.values().forEach(definition -> ordered.putIfAbsent(definition.getId(), definition));
      return List.copyOf(ordered.values());
    }
    return List.copyOf(resolved.values());
  }

  private static String resolveDefaultSchema(
      final String requestedDefaultSchema,
      final String selectedCatalogCode,
      final List<JdbcDataSourceDefinition> mountedDataSources
  ) {
    String explicit = trimToNull(requestedDefaultSchema);
    if (explicit != null) {
      return explicit;
    }
    if (mountedDataSources == null || mountedDataSources.isEmpty()) {
      return null;
    }
    return mountedDataSources.stream()
        .map(JdbcDataSourceDefinition::getCode)
        .map(code -> trimToNull(code))
        .filter(Objects::nonNull)
        .anyMatch(code -> code.equals(selectedCatalogCode))
        ? selectedCatalogCode
        : null;
  }

  private RuntimeException auditFailureAndReturn(
      final RuntimeException exception,
      final FederationQueryModels.SqlConsoleRequest request,
      final PreparedExecution prepared,
      final long startedAt,
      final String status
  ) {
    try {
      persistAudit(
          prepared == null ? trimToNull(request == null ? null : request.catalogCode()) : prepared.catalogCode(),
          prepared == null ? trimToNull(request == null ? null : request.sql()) : prepared.queryRequest().sql(),
          status,
          toElapsedMs(startedAt),
          null,
          prepared == null ? null : prepared.pushdownSummary(),
          resolveMessage(exception)
      );
      return exception;
    } catch (RuntimeException auditException) {
      return new IllegalStateException(
          resolveMessage(exception) + "；同时查询审计写入失败: " + resolveMessage(auditException),
          exception
      );
    }
  }

  private void persistAudit(
      final String catalogCode,
      final String sql,
      final String status,
      final long durationMs,
      final Long resultRows,
      final String pushdownSummary,
      final String errorMessage
  ) {
    FederationQueryAudit audit = new FederationQueryAudit();
    audit.setCatalogCode(trimToNull(catalogCode));
    audit.setStatus(requireValue(status, "审计状态不能为空"));
    audit.setExecutedAt(Instant.now());
    audit.setExecutionTimeMs(durationMs);
    audit.setResultRows(resultRows);
    audit.setExecutedBy(resolveExecutedBy());
    audit.setQueryText(truncate(sql, SQL_TEXT_MAX_LENGTH));
    audit.setPushdownSummary(truncate(pushdownSummary, AUDIT_MESSAGE_MAX_LENGTH));
    audit.setErrorMessage(truncate(errorMessage, AUDIT_MESSAGE_MAX_LENGTH));
    auditService.create(audit);
  }

  private static List<String> resolveResponseDataSources(
      final PreparedExecution prepared,
      final CalciteQueryAnalysis analysis
  ) {
    List<String> detected = extractUsedDataSources(analysis.planText(), prepared.assembly().physicalDataSourceCodes());
    return detected.isEmpty() ? prepared.dataSources() : detected;
  }

  private static List<String> extractUsedDataSources(
      final String planText,
      final List<String> candidateCodes
  ) {
    if (planText == null || planText.isBlank() || candidateCodes == null || candidateCodes.isEmpty()) {
      return List.of();
    }
    Map<String, String> candidatesByLowerCase = new LinkedHashMap<>();
    candidateCodes.forEach(code -> {
      String normalized = trimToNull(code);
      if (normalized != null) {
        candidatesByLowerCase.put(normalized.toLowerCase(Locale.ROOT), normalized);
      }
    });
    LinkedHashSet<String> usedSources = new LinkedHashSet<>();
    Matcher matcher = JDBC_TABLE_SCAN_PATTERN.matcher(planText);
    while (matcher.find()) {
      String[] segments = matcher.group(1).split(",");
      for (String segment : segments) {
        String normalized = trimToNull(segment);
        if (normalized == null) {
          continue;
        }
        String candidate = candidatesByLowerCase.get(normalized.toLowerCase(Locale.ROOT));
        if (candidate != null) {
          usedSources.add(candidate);
        }
      }
    }
    return List.copyOf(usedSources);
  }

  private static TableReferenceSummary collectTableReferences(final String sql) {
    String normalizedSql = trimToNull(sql);
    if (normalizedSql == null) {
      return TableReferenceSummary.empty();
    }
    try {
      SqlParser parser = SqlParser.create(normalizedSql);
      SqlNodeList statements = parser.parseStmtList();
      if (statements.size() != 1 || statements.get(0) == null) {
        return TableReferenceSummary.empty();
      }
      TableReferenceCollector collector = new TableReferenceCollector();
      collector.collectQuery(statements.get(0));
      return collector.summary();
    } catch (SqlParseException ex) {
      return TableReferenceSummary.empty();
    }
  }

  private static String normalizeQualifiedIdentifiers(final String sql) {
    return FederationSqlIdentifierNormalizer.normalize(sql);
  }

  private static String buildPushdownSummary(
      final CalciteQueryAnalysis analysis,
      final List<String> dataSources
  ) {
    List<String> sections = new ArrayList<>();
    sections.add("命中数据源: " + (dataSources == null || dataSources.isEmpty() ? "未识别" : String.join(", ", dataSources)));
    if (analysis.pushedDownOperators().isEmpty()) {
      sections.add("未从执行计划中识别出明确的 JDBC 下推算子");
    } else {
      sections.add("检测到已下推算子: " + String.join(", ", analysis.pushedDownOperators()));
    }
    if (dataSources != null && dataSources.size() > 1) {
      sections.add(analysis.platformJoin()
          ? "检测到跨数据源 Join 保留在平台层执行"
          : "检测到跨数据源访问");
    } else if (analysis.pushedDownOperators().contains("Join")) {
      sections.add("检测到单数据源 Join 已下推到源库");
    }
    return String.join("；", sections);
  }

  private static long toElapsedMs(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private static String resolveMessage(final Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = trimToNull(current.getMessage());
    return message == null ? current.getClass().getSimpleName() : message;
  }

  private static String truncate(final String value, final int maxLength) {
    String normalized = trimToNull(value);
    if (normalized == null || normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength);
  }

  private static String resolveExecutedBy() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return "anonymous";
    }
    String username = trimToNull(authentication.getName());
    return username == null ? "anonymous" : username;
  }

  private record ResolvedPolicy(
      String code,
      int maxRows,
      int timeoutMs,
      boolean allowCrossSourceJoin
  ) {
  }

  private static void closePreparedExecution(final PreparedExecution prepared) {
    if (prepared == null) {
      return;
    }
    prepared.close();
  }

  private record PreparedExecution(
      String catalogCode,
      ResolvedPolicy policy,
      FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly,
      CalciteQueryRequest queryRequest,
      CalciteQueryAnalysis analysis,
      List<String> dataSources,
      boolean crossSourceJoin,
      String pushdownSummary
  ) implements AutoCloseable {

    @Override
    public void close() {
      assembly.close();
    }
  }

  private record TableReferenceSummary(
      List<List<String>> identifiers
  ) {

    private static TableReferenceSummary empty() {
      return new TableReferenceSummary(List.of());
    }

    private boolean hasTableReferences() {
      return identifiers != null && !identifiers.isEmpty();
    }
  }

  private static final class TableReferenceCollector {

    private final List<List<String>> identifiers = new ArrayList<>();

    private final LinkedHashSet<String> commonTableExpressionNames = new LinkedHashSet<>();

    private void collectQuery(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlWith with) {
        for (SqlNode item : with.withList) {
          if (item instanceof SqlWithItem withItem) {
            String cteName = trimToNull(withItem.name == null ? null : withItem.name.getSimple());
            if (cteName != null) {
              commonTableExpressionNames.add(cteName.toLowerCase(Locale.ROOT));
            }
            collectQuery(withItem.query);
          }
        }
        collectQuery(with.body);
        return;
      }
      if (node instanceof SqlOrderBy orderBy) {
        collectQuery(orderBy.query);
        collectNestedQueries(orderBy.orderList);
        return;
      }
      if (node instanceof SqlInsert insert) {
        collectFrom(insert.getTargetTable());
        collectQuery(insert.getSource());
        return;
      }
      if (node instanceof SqlUpdate update) {
        collectFrom(update.getTargetTable());
        collectNestedQueries(update.getSourceExpressionList());
        collectNestedQueries(update.getCondition());
        return;
      }
      if (node instanceof SqlDelete delete) {
        collectFrom(delete.getTargetTable());
        collectNestedQueries(delete.getCondition());
        return;
      }
      if (node instanceof SqlMerge merge) {
        collectFrom(merge.getTargetTable());
        collectQuery(merge.getSourceTableRef());
        collectNestedQueries(merge.getCondition());
        if (merge.getUpdateCall() != null) {
          collectQuery(merge.getUpdateCall());
        }
        if (merge.getInsertCall() != null) {
          collectQuery(merge.getInsertCall());
        }
        return;
      }
      if (node instanceof SqlSelect select) {
        collectFrom(select.getFrom());
        collectNestedQueries(select.getSelectList());
        collectNestedQueries(select.getWhere());
        collectNestedQueries(select.getHaving());
        collectNestedQueries(select.getGroup());
        collectNestedQueries(select.getOrderList());
        return;
      }
      if (node instanceof SqlCall call) {
        collectNestedQueries(call);
      }
    }

    private void collectFrom(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlIdentifier identifier) {
        addIdentifier(identifier);
        return;
      }
      if (node instanceof SqlJoin join) {
        collectFrom(join.getLeft());
        collectFrom(join.getRight());
        collectNestedQueries(join.getCondition());
        return;
      }
      if (node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy) {
        collectQuery(node);
        return;
      }
      if (node instanceof SqlNodeList nodeList) {
        nodeList.forEach(this::collectFrom);
        return;
      }
      if (node instanceof SqlBasicCall call) {
        if (SqlKind.AS.equals(call.getKind()) && !call.getOperandList().isEmpty()) {
          collectFrom(call.getOperandList().get(0));
          return;
        }
        collectNestedQueries(call);
        return;
      }
      if (node instanceof SqlCall call) {
        collectNestedQueries(call);
      }
    }

    private void collectNestedQueries(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlSelect || node instanceof SqlWith || node instanceof SqlOrderBy) {
        collectQuery(node);
        return;
      }
      if (node instanceof SqlNodeList nodeList) {
        nodeList.forEach(this::collectNestedQueries);
        return;
      }
      if (node instanceof SqlCall call) {
        for (SqlNode operand : call.getOperandList()) {
          collectNestedQueries(operand);
        }
      }
    }

    private void addIdentifier(final SqlIdentifier identifier) {
      if (identifier == null || identifier.names == null || identifier.names.isEmpty()) {
        return;
      }
      List<String> names = identifier.names.stream()
          .map(name -> trimToNull(name))
          .filter(Objects::nonNull)
          .toList();
      if (names.isEmpty()) {
        return;
      }
      if (names.size() == 1 && commonTableExpressionNames.contains(names.get(0).toLowerCase(Locale.ROOT))) {
        return;
      }
      identifiers.add(names);
    }

    private TableReferenceSummary summary() {
      return new TableReferenceSummary(List.copyOf(identifiers));
    }
  }

  /**
   * Rewrites DML SQL by stripping the federation datasource code prefix from table identifiers.
   * Uses position-based string replacement via Calcite SqlIdentifier positions.
   */
  private static final class DmlTableRewriter {

    private final String originalSql;

    private final String dataSourceCode;

    private final List<IdentifierReplacement> replacements = new ArrayList<>();

    private DmlTableRewriter(final String originalSql, final String dataSourceCode) {
      this.originalSql = originalSql;
      this.dataSourceCode = dataSourceCode;
    }

    private void rewrite(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlInsert insert) {
        rewriteTableIdentifier(insert.getTargetTable());
        rewriteNestedDml(insert.getSource());
        return;
      }
      if (node instanceof SqlUpdate update) {
        rewriteTableIdentifier(update.getTargetTable());
        return;
      }
      if (node instanceof SqlDelete delete) {
        rewriteTableIdentifier(delete.getTargetTable());
        return;
      }
      if (node instanceof SqlMerge merge) {
        rewriteTableIdentifier(merge.getTargetTable());
        return;
      }
    }

    private void rewriteNestedDml(final SqlNode node) {
      if (node instanceof SqlSelect select) {
        rewriteSelectFrom(select.getFrom());
      }
    }

    private void rewriteSelectFrom(final SqlNode node) {
      if (node == null) {
        return;
      }
      if (node instanceof SqlIdentifier identifier) {
        rewriteTableIdentifier(identifier);
        return;
      }
      if (node instanceof SqlJoin join) {
        rewriteSelectFrom(join.getLeft());
        rewriteSelectFrom(join.getRight());
        return;
      }
      if (node instanceof SqlBasicCall call) {
        if (SqlKind.AS.equals(call.getKind()) && !call.getOperandList().isEmpty()) {
          rewriteSelectFrom(call.getOperandList().get(0));
        }
      }
    }

    private void rewriteTableIdentifier(final SqlNode node) {
      if (!(node instanceof SqlIdentifier identifier)) {
        return;
      }
      if (identifier.names == null || identifier.names.size() < 2) {
        return;
      }
      String firstSegment = trimToNull(identifier.names.get(0));
      if (firstSegment == null || !firstSegment.equalsIgnoreCase(dataSourceCode)) {
        return;
      }
      // Strip the datasource code prefix — rest becomes native table reference
      List<String> nativeNames = identifier.names.subList(1, identifier.names.size());
      String nativeRef = String.join(".", nativeNames);
      var pos = identifier.getParserPosition();
      if (pos != null && pos.getLineNum() > 0) {
        replacements.add(new IdentifierReplacement(
            pos.getLineNum(),
            pos.getColumnNum(),
            pos.getEndLineNum(),
            pos.getEndColumnNum(),
            nativeRef
        ));
      }
    }

    private String apply() {
      if (replacements.isEmpty()) {
        return originalSql;
      }
      // Sort replacements from end to start so position indices remain valid
      replacements.sort(Comparator
          .comparingInt(IdentifierReplacement::endLine).reversed()
          .thenComparingInt(IdentifierReplacement::endColumn).reversed()
      );
      String[] lines = originalSql.split("\n", -1);
      for (IdentifierReplacement replacement : replacements) {
        int startLineIdx = replacement.startLine() - 1;
        int endLineIdx = replacement.endLine() - 1;
        if (startLineIdx < 0 || endLineIdx >= lines.length) {
          continue;
        }
        if (startLineIdx == endLineIdx) {
          String line = lines[startLineIdx];
          int startCol = replacement.startColumn() - 1;
          int endCol = replacement.endColumn();
          if (startCol >= 0 && endCol <= line.length()) {
            lines[startLineIdx] = line.substring(0, startCol) + replacement.replacement() + line.substring(endCol);
          }
        }
      }
      return String.join("\n", lines);
    }

    private record IdentifierReplacement(
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String replacement
    ) {
    }
  }

  private static final class PolicyViolationException extends IllegalStateException {

    private PolicyViolationException(final String message) {
      super(message);
    }
  }
}
