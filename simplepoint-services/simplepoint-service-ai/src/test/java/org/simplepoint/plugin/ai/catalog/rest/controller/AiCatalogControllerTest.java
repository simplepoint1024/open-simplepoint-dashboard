package org.simplepoint.plugin.ai.catalog.rest.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogErrorCode;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncErrorCode;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncResult;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncMode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncStatus;
import org.simplepoint.plugin.ai.catalog.api.service.AiCatalogService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiCatalogControllerTest {

  private AiCatalogService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(AiCatalogService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AiCatalogController(
                    service,
                    mock(org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.class)))
            .setCustomArgumentResolvers(
                new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
            .build();
  }

  @Test
  void syncReturnsStableErrorCodeWithoutLegacyErrorProse() throws Exception {
    when(service.syncOfficialRegistry())
        .thenReturn(
            new AiCatalogSyncResult(
                CatalogSource.OFFICIAL_MCP,
                CatalogSyncMode.INCREMENTAL,
                CatalogSyncStatus.FAILED,
                0,
                null,
                null,
                AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_FAILED));

    mockMvc
        .perform(post("/workbench/catalog/sync"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$.errorCode").value(AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_FAILED.name()))
        .andExpect(jsonPath("$.error").doesNotExist());
  }

  @Test
  void headerQuerySchemaAndTypedPagingReachTheMixedSourceQueryAdapter() throws Exception {
    when(service.querySchema())
        .thenReturn(
            java.util.Map.of(
                "schema", java.util.Map.of("type", "object"), "buttons", java.util.List.of()));
    when(service.query(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new org.simplepoint.plugin.ai.catalog.api.model.AiCatalogPage(
                java.util.List.of(), 3, 10, 0, 0));
    mockMvc
        .perform(get("/workbench/catalog/schema"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.type").value("object"));
    mockMvc
        .perform(
            get("/workbench/catalog")
                .param("page", "3")
                .param("size", "10")
                .param("title", "like:Node")
                .param("source", "equals:OFFICIAL_MCP")
                .param("sort", "title,desc", "version,asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.number").value(3));
    var pages = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    org.mockito.Mockito.verify(service)
        .query(
            org.mockito.ArgumentMatchers.argThat(
                filters ->
                    "like:Node".equals(filters.get("title"))
                        && "equals:OFFICIAL_MCP".equals(filters.get("source"))),
            pages.capture());
    org.assertj.core.api.Assertions.assertThat(pages.getValue())
        .isEqualTo(
            org.springframework.data.domain.PageRequest.of(
                3,
                10,
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.desc("title"),
                    org.springframework.data.domain.Sort.Order.asc("version"))));
    org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
        .findAll(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void syncUnexpectedFailureDoesNotExposeServerProse() throws Exception {
    String privateProse = "database topology and registry credentials";
    when(service.syncOfficialRegistry()).thenThrow(new IllegalStateException(privateProse));

    mockMvc
        .perform(post("/workbench/catalog/sync"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(CatalogSyncStatus.FAILED.name()))
        .andExpect(
            jsonPath("$.errorCode").value(AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_FAILED.name()))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(privateProse))));
  }

  @Test
  void importValidationFailureReturnsStableJsonWithoutServerProse() throws Exception {
    String privateProse = "internal catalog entry and tenant details";
    when(service.importOfficialMcpServer("catalog-1", null))
        .thenThrow(new IllegalArgumentException(privateProse));

    mockMvc
        .perform(post("/workbench/catalog/catalog-1/import"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$.errorCode").value(AiCatalogErrorCode.AI_CATALOG_REQUEST_INVALID.name()))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(privateProse))));
  }

  @Test
  void runtimeImportStateFailureReturnsStableJsonWithoutServerProse() throws Exception {
    String privateProse = "private descriptor parser details";
    when(service.importOfficialMcpPackage("catalog-1", null))
        .thenThrow(new IllegalStateException(privateProse));

    mockMvc
        .perform(post("/workbench/catalog/catalog-1/import-runtime"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$.errorCode")
                .value(AiCatalogErrorCode.AI_CATALOG_OPERATION_UNAVAILABLE.name()))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(privateProse))));
  }

  @Test
  void unexpectedCatalogFailureReturnsStableJsonWithoutServerProse() throws Exception {
    String privateProse = "database topology and registry credentials";
    when(service.query(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new RuntimeException(privateProse));

    mockMvc
        .perform(get("/workbench/catalog"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$.errorCode").value(AiCatalogErrorCode.AI_CATALOG_OPERATION_FAILED.name()))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(privateProse))));
  }
}
