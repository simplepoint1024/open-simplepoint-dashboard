package org.simplepoint.plugin.dna.federation.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.query.FilterCondition;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.DataQualityRule;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.service.DataAssetService;
import org.simplepoint.plugin.dna.federation.api.service.DataQualityRuleService;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryTemplateService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class DashboardControllerTest {
  @Test
  void summaryUsesDeclaredQueryFieldsAndBoundedCountPages() {
    var sources = mock(JdbcDataSourceDefinitionService.class);
    var templates = mock(FederationQueryTemplateService.class);
    var audits = mock(FederationQueryAuditService.class);
    var assets = mock(DataAssetService.class);
    var quality = mock(DataQualityRuleService.class);
    when(sources.listEnabledDefinitions()).thenReturn(List.of());
    when(audits.limit(any(), any(Pageable.class))).thenAnswer(invocation -> {
      Map<String, String> filters = invocation.getArgument(0);
      Pageable page = invocation.getArgument(1);
      assertTrue(page.getPageSize() <= QueryCapabilities.declaration(FederationQueryAudit.class).maxPageSize());
      page.getSort().forEach(order -> QueryCapabilities.require(
          FederationQueryAudit.class, order.getProperty(), null, true));
      filters.forEach((field, value) -> {
        if (!field.equals("deletedAt")) {
          QueryCapabilities.require(FederationQueryAudit.class, field,
              FilterCondition.parse(value).operator(), false);
        }
      });
      return new PageImpl<FederationQueryAudit>(List.of(), page,
          filters.containsKey("executedAt") ? 2 : 9);
    });
    when(quality.limit(any(), any(Pageable.class))).thenAnswer(invocation -> {
      Map<String, String> filters = invocation.getArgument(0);
      Pageable page = invocation.getArgument(1);
      assertEquals(1, page.getPageSize());
      filters.forEach((field, value) -> QueryCapabilities.require(DataQualityRule.class,
          field, FilterCondition.parse(value).operator(), false));
      return new PageImpl<DataQualityRule>(List.of(), page, filters.isEmpty() ? 7 : 1);
    });

    var body = (Map<?, ?>) new DashboardController(sources, templates, audits, assets, quality)
        .summary().getBody();
    assertEquals(9L, body.get("totalQueries"));
    assertEquals(7, ((List<?>) body.get("queryTrend")).size());
    assertEquals(7L, ((Map<?, ?>) body.get("qualityStats")).get("total"));
    verify(audits, times(8)).limit(any(), any(Pageable.class));
    verify(quality, times(6)).limit(any(), any(Pageable.class));
  }
}
