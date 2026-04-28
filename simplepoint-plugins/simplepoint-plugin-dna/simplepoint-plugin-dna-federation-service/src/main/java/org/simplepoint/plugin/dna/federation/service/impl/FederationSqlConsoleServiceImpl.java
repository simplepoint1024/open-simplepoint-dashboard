package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.simplepoint.data.calcite.core.query.CalciteQueryAnalysis;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDdlStatementProcessor.DdlTarget;
import org.simplepoint.plugin.dna.federation.service.impl.FederationDmlStatementProcessor.DmlTarget;
import org.simplepoint.plugin.dna.federation.service.impl.FederationSqlAnalysisUtils.TableReferenceSummary;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler;
import org.simplepoint.plugin.dna.federation.service.support.FederationMetadataCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Federation SQL console service implementation.
 */
@Service
public class FederationSqlConsoleServiceImpl implements FederationSqlConsoleService {

  private static final Logger LOGGER = LoggerFactory.getLogger(FederationSqlConsoleServiceImpl.class);

  /** Default statement timeout (in seconds) for DDL and DML execution to prevent indefinite hangs. */
  private static final int DDL_DML_STATEMENT_TIMEOUT_SECONDS = 60;

  private static final Pattern SMART_DML_PATTERN = Pattern.compile(
      "\\s*(INSERT|UPDATE|DELETE|MERGE|UPSERT)\\b", Pattern.CASE_INSENSITIVE
  );

  private static final Pattern SMART_FLUSH_CACHE_PATTERN = Pattern.compile(
      "\\s*FLUSH\\s+CACHE\\s*", Pattern.CASE_INSENSITIVE
  );

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final FederationQueryPolicyRepository policyRepository;

  private final FederationCalciteCatalogAssembler catalogAssembler;

  private final CalciteQueryEngine queryEngine;

  private final FederationMetadataCacheService metadataCacheService;

  private final FederationDmlStatementProcessor dmlProcessor;

  private final FederationDdlStatementProcessor ddlProcessor;

  private final FederationSqlAuditor sqlAuditor;

  /**
   * Creates a SQL console service implementation.
   *
   * @param dataSourceService datasource service
   * @param policyRepository query policy repository
   * @param auditService query audit service
   * @param catalogAssembler Calcite catalog assembler
   * @param queryEngine Calcite query engine
   * @param metadataCacheService metadata cache service
   */
  public FederationSqlConsoleServiceImpl(
      final JdbcDataSourceDefinitionService dataSourceService,
      final FederationQueryPolicyRepository policyRepository,
      final FederationQueryAuditService auditService,
      final FederationCalciteCatalogAssembler catalogAssembler,
      final CalciteQueryEngine queryEngine,
      final FederationMetadataCacheService metadataCacheService
  ) {
    this.dataSourceService = dataSourceService;
    this.policyRepository = policyRepository;
    this.catalogAssembler = catalogAssembler;
    this.queryEngine = queryEngine;
    this.metadataCacheService = metadataCacheService;
    this.dmlProcessor = new FederationDmlStatementProcessor(dataSourceService);
    this.ddlProcessor = new FederationDdlStatementProcessor(dataSourceService);
    this.sqlAuditor = new FederationSqlAuditor(auditService);
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
      CalciteQueryResult result = queryEngine.execute(
          prepared.queryRequest(),
          prepared.assembly().schemaConfigurer(),
          prepared.analysis()
      );
      List<String> resultSources = FederationSqlAnalysisUtils.resolveResponseDataSources(
          result.analysis().planText(),
          prepared.assembly().physicalDataSourceCodes(),
          prepared.dataSources()
      );
      String pushdownSummary = FederationSqlAnalysisUtils.buildPushdownSummary(result.analysis(), resultSources);
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
      sqlAuditor.persist(
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
    if (sql.length() > FederationSqlAuditor.SQL_TEXT_MAX_LENGTH) {
      throw new IllegalArgumentException("SQL 长度不能超过 " + FederationSqlAuditor.SQL_TEXT_MAX_LENGTH + " 个字符");
    }
    String normalizedSql = FederationSqlAnalysisUtils.normalizeQualifiedIdentifiers(sql);
    JdbcDataSourceDefinition resolvedDataSource = resolveDataSource(dataSourceId, catalogCode);
    TableReferenceSummary references = FederationSqlAnalysisUtils.collectTableReferences(normalizedSql);
    DmlTarget dmlTarget = dmlProcessor.resolve(resolvedDataSource, references);
    SimpleDataSource simpleDataSource = dataSourceService.requireSimpleDataSource(
        requireValue(dmlTarget.dataSource().getId(), "数据源ID不能为空")
    );
    String pushedSql = FederationDmlStatementProcessor.rewrite(normalizedSql, dmlTarget.dataSource().getCode());
    LOGGER.debug("DML 下推到物理数据源 [{}]: {}", dmlTarget.dataSource().getCode(), pushedSql);
    long startedAt = System.nanoTime();
    try (Connection connection = simpleDataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(pushedSql)) {
      statement.setQueryTimeout(DDL_DML_STATEMENT_TIMEOUT_SECONDS);
      bindParameters(statement, request == null ? null : request.parameters());
      int affectedRows = statement.executeUpdate();
      long elapsed = FederationSqlAuditor.toElapsedMs(startedAt);
      sqlAuditor.persist(
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
      long elapsed = FederationSqlAuditor.toElapsedMs(startedAt);
      sqlAuditor.persist(
          catalogCode,
          normalizedSql,
          "FAILED",
          elapsed,
          null,
          "DML 执行失败 - 目标数据源: " + dmlTarget.dataSource().getCode(),
          FederationSqlAuditor.resolveMessage(ex)
      );
      throw new IllegalStateException("DML 执行失败: " + ex.getMessage(), ex);
    }
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlUpdateResult executeDdl(
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    return executeDdl((String) null, request);
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlUpdateResult executeDdl(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    String catalogCode = requireValue(request == null ? null : request.catalogCode(), "数据源编码不能为空");
    String sql = requireValue(request == null ? null : request.sql(), "SQL 不能为空");
    if (sql.length() > FederationSqlAuditor.SQL_TEXT_MAX_LENGTH) {
      throw new IllegalArgumentException("SQL 长度不能超过 " + FederationSqlAuditor.SQL_TEXT_MAX_LENGTH + " 个字符");
    }
    JdbcDataSourceDefinition resolvedDataSource = resolveDataSource(dataSourceId, catalogCode);
    DdlTarget ddlTarget = ddlProcessor.resolve(resolvedDataSource, sql);
    SimpleDataSource simpleDataSource = dataSourceService.requireSimpleDataSource(
        requireValue(ddlTarget.dataSource().getId(), "数据源ID不能为空")
    );
    String pushedSql = FederationDdlStatementProcessor.rewrite(sql, ddlTarget.dataSource().getCode());
    LOGGER.debug("DDL 下推到物理数据源 [{}]: {}", ddlTarget.dataSource().getCode(), pushedSql);
    long startedAt = System.nanoTime();
    List<Object> params = request == null ? null : request.parameters();
    boolean hasParams = params != null && !params.isEmpty();
    try (Connection connection = simpleDataSource.getConnection()) {
      int result;
      if (hasParams) {
        try (PreparedStatement ps = connection.prepareStatement(pushedSql)) {
          ps.setQueryTimeout(DDL_DML_STATEMENT_TIMEOUT_SECONDS);
          bindParameters(ps, params);
          result = ps.executeUpdate();
        }
      } else {
        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(DDL_DML_STATEMENT_TIMEOUT_SECONDS);
          result = statement.executeUpdate(pushedSql);
        }
      }
      long elapsed = FederationSqlAuditor.toElapsedMs(startedAt);
      sqlAuditor.persist(
          catalogCode,
          sql,
          "SUCCESS",
          elapsed,
          (long) result,
          "DDL 直接下推到物理数据源: " + ddlTarget.dataSource().getCode(),
          null
      );
      return new FederationQueryModels.SqlUpdateResult(
          catalogCode,
          ddlTarget.dataSource().getCode(),
          result,
          elapsed,
          pushedSql
      );
    } catch (SQLException ex) {
      long elapsed = FederationSqlAuditor.toElapsedMs(startedAt);
      sqlAuditor.persist(
          catalogCode,
          sql,
          "FAILED",
          elapsed,
          null,
          "DDL 执行失败 - 目标数据源: " + ddlTarget.dataSource().getCode(),
          FederationSqlAuditor.resolveMessage(ex)
      );
      throw new IllegalStateException("DDL 执行失败: " + ex.getMessage(), ex);
    }
  }

  // ------------------------------------------------------------------
  // Smart execute — auto-detect SQL type and dispatch
  // ------------------------------------------------------------------

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlExecuteResult smartExecute(
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    String sql = requireValue(request == null ? null : request.sql(), "SQL 不能为空");
    String trimmed = sql.strip();

    if (SMART_FLUSH_CACHE_PATTERN.matcher(trimmed).matches()) {
      long flushed = metadataCacheService.flushAll();
      return FederationQueryModels.SqlExecuteResult.flushCache(
          "缓存已刷新，共清除 " + flushed + " 条缓存记录"
      );
    }
    if (FederationDdlStatementProcessor.DDL_PREFIX_PATTERN.matcher(trimmed).lookingAt()) {
      return FederationQueryModels.SqlExecuteResult.ddl(executeDdl(request));
    }
    if (SMART_DML_PATTERN.matcher(trimmed).lookingAt()) {
      return FederationQueryModels.SqlExecuteResult.dml(executeUpdate(request));
    }
    return FederationQueryModels.SqlExecuteResult.query(execute(request));
  }

  // ------------------------------------------------------------------
  // Internal helpers
  // ------------------------------------------------------------------

  private PreparedExecution prepare(
      final String dataSourceId,
      final FederationQueryModels.SqlConsoleRequest request
  ) {
    String catalogCode = requireValue(request == null ? null : request.catalogCode(), "数据源编码不能为空");
    String sql = requireValue(request == null ? null : request.sql(), "SQL 不能为空");
    if (sql.length() > FederationSqlAuditor.SQL_TEXT_MAX_LENGTH) {
      throw new IllegalArgumentException("SQL 长度不能超过 " + FederationSqlAuditor.SQL_TEXT_MAX_LENGTH + " 个字符");
    }
    String normalizedSql = FederationSqlAnalysisUtils.normalizeQualifiedIdentifiers(sql);
    JdbcDataSourceDefinition resolvedDataSource = resolveDataSource(dataSourceId, catalogCode);
    ResolvedPolicy policy = resolvePolicy(resolvedDataSource);
    List<JdbcDataSourceDefinition> queryDataSources = resolveQueryDataSources(resolvedDataSource, normalizedSql);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = catalogAssembler.assemble(
        resolvedDataSource.getCode(),
        queryDataSources
    );
    try {
      int effectiveMaxRows = policy.maxRows();
      if (request != null && request.maxRows() != null && request.maxRows() > 0) {
        effectiveMaxRows = Math.min(effectiveMaxRows, request.maxRows());
      }
      CalciteQueryRequest queryRequest = new CalciteQueryRequest(
          normalizedSql,
          resolveDefaultSchema(request == null ? null : request.defaultSchema(), resolvedDataSource.getCode(), queryDataSources),
          effectiveMaxRows,
          policy.timeoutMs(),
          request == null ? null : request.parameters()
      );
      CalciteQueryAnalysis analysis = queryEngine.explain(queryRequest, assembly.schemaConfigurer());
      List<String> dataSources = FederationSqlAnalysisUtils.extractUsedDataSources(
          analysis.planText(), assembly.physicalDataSourceCodes()
      );
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
          FederationSqlAnalysisUtils.buildPushdownSummary(analysis, dataSources)
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
    TableReferenceSummary references = FederationSqlAnalysisUtils.collectTableReferences(sql);
    if (!references.hasTableReferences()) {
      return List.of();
    }
    List<JdbcDataSourceDefinition> enabledDataSources = dataSourceService.listEnabledDefinitions();
    LinkedHashMap<String, JdbcDataSourceDefinition> dataSourcesByCode = new LinkedHashMap<>();
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
    String catalogCode = prepared == null
        ? trimToNull(request == null ? null : request.catalogCode())
        : prepared.catalogCode();
    String sql = prepared == null
        ? trimToNull(request == null ? null : request.sql())
        : prepared.queryRequest().sql();
    String pushdownSummary = prepared == null ? null : prepared.pushdownSummary();
    return sqlAuditor.recordFailure(exception, catalogCode, sql, startedAt, status, pushdownSummary);
  }

  private static void closePreparedExecution(final PreparedExecution prepared) {
    if (prepared == null) {
      return;
    }
    prepared.close();
  }

  private static final class PolicyViolationException extends IllegalStateException {

    private PolicyViolationException(final String message) {
      super(message);
    }
  }

  private record ResolvedPolicy(
      String code,
      int maxRows,
      int timeoutMs,
      boolean allowCrossSourceJoin
  ) {
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

  /**
   * Binds parameters to a prepared statement using {@code setObject}.
   * If parameters is {@code null} or empty, this is a no-op.
   */
  private static void bindParameters(
      final PreparedStatement statement,
      final List<Object> parameters
  ) throws SQLException {
    if (parameters == null || parameters.isEmpty()) {
      return;
    }
    for (int i = 0; i < parameters.size(); i++) {
      Object value = parameters.get(i);
      if (value == null) {
        statement.setNull(i + 1, java.sql.Types.NULL);
      } else {
        statement.setObject(i + 1, value);
      }
    }
  }
}
