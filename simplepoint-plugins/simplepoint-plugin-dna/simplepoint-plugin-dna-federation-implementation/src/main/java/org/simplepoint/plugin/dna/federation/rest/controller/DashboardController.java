package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.vo.DataSourceHealthStatus;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.service.DataAssetService;
import org.simplepoint.plugin.dna.federation.api.service.DataQualityRuleService;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryTemplateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DNA workbench dashboard aggregation endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.DASHBOARD, DnaFederationPaths.PLATFORM_DASHBOARD})
@Tag(name = "DNA Dashboard", description = "DNA 工作台仪表盘汇总接口")
public class DashboardController {

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final FederationQueryTemplateService templateService;

  private final FederationQueryAuditService auditService;

  private final DataAssetService assetService;

  private final DataQualityRuleService qualityRuleService;

  /**
   * Creates a dashboard controller.
   *
   * @param dataSourceService  datasource service
   * @param templateService    template service
   * @param auditService       audit service
   * @param assetService       asset service
   * @param qualityRuleService data quality rule service
   */
  public DashboardController(
      final JdbcDataSourceDefinitionService dataSourceService,
      final FederationQueryTemplateService templateService,
      final FederationQueryAuditService auditService,
      final DataAssetService assetService,
      final DataQualityRuleService qualityRuleService
  ) {
    this.dataSourceService = dataSourceService;
    this.templateService = templateService;
    this.auditService = auditService;
    this.assetService = assetService;
    this.qualityRuleService = qualityRuleService;
  }

  /**
   * Returns aggregated summary for the dashboard.
   *
   * @return summary map
   */
  @GetMapping("/summary")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.dashboard.view')")
  @Operation(summary = "仪表盘汇总", description = "获取 DNA 工作台仪表盘汇总数据")
  public Response<?> summary() {
    Map<String, Object> summary = new LinkedHashMap<>();

    // datasource counts
    var defs = dataSourceService.listEnabledDefinitions();
    summary.put("dataSourceCount", defs.size());

    // template count
    summary.put("templateCount", templateService.countActivePublic() + templateService.countActivePrivate());

    // asset count
    summary.put("assetCount", assetService.countActive());

    // recent audits
    Map<String, String> auditAttrs = new LinkedHashMap<>();
    auditAttrs.put("deletedAt", "is:null");
    Page<FederationQueryAudit> recentAudits = auditService.limit(
        auditAttrs,
        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "executedAt"))
    );
    summary.put("recentAudits", recentAudits.getContent());
    summary.put("totalQueries", recentAudits.getTotalElements());

    // health overview (top 10)
    var top = defs.size() > 10 ? defs.subList(0, 10) : defs;
    List<DataSourceHealthStatus> healthList = top.stream().map(def -> {
      long start = System.currentTimeMillis();
      String status;
      String errorMessage = null;
      try {
        dataSourceService.connect(def.getId());
        status = "UP";
      } catch (Exception ex) {
        status = "DOWN";
        errorMessage = ex.getMessage();
      }
      long elapsed = System.currentTimeMillis() - start;
      return new DataSourceHealthStatus(
          def.getId(), def.getCode(), def.getName(),
          def.getDriverName(), status, elapsed, errorMessage
      );
    }).toList();
    summary.put("healthOverview", healthList);

    // Count each UTC day in SQL. Fetching a fixed number of audit rows would truncate busy days.
    List<Map<String, Object>> queryTrend = new ArrayList<>();
    for (int i = 6; i >= 0; i--) {
      LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(i);
      String start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
      String end = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1).toString();
      long count = auditService.limit(Map.of("executedAt", "between:" + start + "," + end),
          PageRequest.of(0, 1)).getTotalElements();
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("date", day.toString());
      point.put("count", count);
      queryTrend.add(point);
    }
    summary.put("queryTrend", queryTrend);

    // Count status groups without requesting a page larger than the published query contract.
    long qualityTotal = qualityCount(null);
    long qualityPassed = qualityCount("equals:PASSED");
    long qualityFailed = qualityCount("equals:FAILED");
    long qualityError = qualityCount("equals:ERROR");
    long qualityNotRun = qualityCount("is:null") + qualityCount("equals:NOT_RUN");
    Map<String, Object> qualityStats = new LinkedHashMap<>();
    qualityStats.put("total", qualityTotal);
    qualityStats.put("passed", qualityPassed);
    qualityStats.put("failed", qualityFailed);
    qualityStats.put("error", qualityError);
    qualityStats.put("notRun", qualityNotRun);
    summary.put("qualityStats", qualityStats);

    return Response.okay(summary);
  }

  private long qualityCount(String status) {
    Map<String, String> filters = status == null ? Map.of() : Map.of("lastRunStatus", status);
    return qualityRuleService.limit(filters, PageRequest.of(0, 1)).getTotalElements();
  }
}
