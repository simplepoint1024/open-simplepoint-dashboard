package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.simplepoint.data.calcite.core.query.CalciteQueryAnalysis;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler;
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

  private final FederationCatalogService catalogService;

  private final FederationQueryPolicyRepository policyRepository;

  private final FederationQueryAuditService auditService;

  private final FederationCalciteCatalogAssembler catalogAssembler;

  private final CalciteQueryEngine queryEngine;

  /**
   * Creates a SQL console service implementation.
   *
   * @param catalogService   federation catalog service
   * @param policyRepository query policy repository
   * @param auditService     query audit service
   * @param catalogAssembler Calcite catalog assembler
   * @param queryEngine      Calcite query engine
   */
  public FederationSqlConsoleServiceImpl(
      final FederationCatalogService catalogService,
      final FederationQueryPolicyRepository policyRepository,
      final FederationQueryAuditService auditService,
      final FederationCalciteCatalogAssembler catalogAssembler,
      final CalciteQueryEngine queryEngine
  ) {
    this.catalogService = catalogService;
    this.policyRepository = policyRepository;
    this.auditService = auditService;
    this.catalogAssembler = catalogAssembler;
    this.queryEngine = queryEngine;
  }

  /** {@inheritDoc} */
  @Override
  public FederationQueryModels.SqlExplainResult explain(final FederationQueryModels.SqlConsoleRequest request) {
    try (PreparedExecution prepared = prepare(request)) {
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
    PreparedExecution prepared = null;
    long startedAt = System.nanoTime();
    try {
      prepared = prepare(request);
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
              .map(column -> new FederationQueryModels.SqlColumn(column.name(), column.typeName()))
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

  private PreparedExecution prepare(final FederationQueryModels.SqlConsoleRequest request) {
    String catalogCode = requireValue(request == null ? null : request.catalogCode(), "联邦目录编码不能为空");
    String sql = requireValue(request == null ? null : request.sql(), "SQL 不能为空");
    if (sql.length() > SQL_TEXT_MAX_LENGTH) {
      throw new IllegalArgumentException("SQL 长度不能超过 " + SQL_TEXT_MAX_LENGTH + " 个字符");
    }
    FederationCatalog catalog = resolveCatalog(catalogCode);
    ResolvedPolicy policy = resolvePolicy(catalog);
    FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly assembly = catalogAssembler.assemble(catalog);
    try {
      CalciteQueryRequest queryRequest = new CalciteQueryRequest(
          sql,
          catalog.getCode(),
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
          catalog.getCode(),
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

  private FederationCatalog resolveCatalog(final String catalogCode) {
    FederationCatalog catalog = catalogService.findActiveByCode(catalogCode)
        .orElseThrow(() -> new PolicyViolationException("联邦目录不存在: " + catalogCode));
    if (!Boolean.TRUE.equals(catalog.getEnabled())) {
      throw new PolicyViolationException("联邦目录已禁用: " + catalogCode);
    }
    return catalog;
  }

  private ResolvedPolicy resolvePolicy(final FederationCatalog catalog) {
    FederationQueryPolicy policy = policyRepository.findAllActiveByCatalogId(catalog.getId()).stream()
        .filter(candidate -> Boolean.TRUE.equals(candidate.getEnabled()))
        .sorted(Comparator.comparing(
            FederationQueryPolicy::getUpdatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(
            FederationQueryPolicy::getCreatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())
        ).thenComparing(FederationQueryPolicy::getCode, Comparator.nullsLast(String::compareTo)))
        .findFirst()
        .orElseThrow(() -> new PolicyViolationException("联邦目录未配置已启用的查询策略: " + catalog.getCode()));
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

  private static final class PolicyViolationException extends IllegalStateException {

    private PolicyViolationException(final String message) {
      super(message);
    }
  }
}
