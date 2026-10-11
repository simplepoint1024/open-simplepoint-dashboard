package org.simplepoint.common.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantResourceController;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Tests actual MVC name binding without relying on compiler reflection parameter metadata. */
class TenantResourceQueryHttpTest {
  private TenantResourceCatalog catalog;
  private MockMvc http;

  @BeforeEach
  void setup() {
    catalog = mock(TenantResourceCatalog.class);
    http = MockMvcBuilders.standaloneSetup(new TenantResourceController(catalog))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    when(catalog.query(anyMap(), nullable(String.class), anyBoolean(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
    when(catalog.querySchema()).thenReturn(Map.of("properties", Map.of()));
  }

  @Test
  void realQueryBindingPreservesFixedParentHeaderConditionsAndRepeatedSorts() throws Exception {
    http.perform(get("/tenants/current-resources").param("parentId", "parent")
        .param("rootsOnly", "true").param("name", "like:项目").param("page", "2").param("size", "20")
        .param("sort", "name,asc", "code,desc")).andExpect(status().isOk());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> filters = ArgumentCaptor.forClass(Map.class);
    var page = ArgumentCaptor.forClass(Pageable.class);
    verify(catalog).query(filters.capture(), org.mockito.ArgumentMatchers.eq("parent"),
        org.mockito.ArgumentMatchers.eq(true), page.capture());
    assertThat(filters.getValue()).containsEntry("name", "like:项目");
    assertThat(page.getValue().getPageNumber()).isEqualTo(2);
    assertThat(page.getValue().getSort()).extracting(order -> order.getProperty() + ":" + order.getDirection())
        .containsExactly("name:ASC", "code:DESC");
    http.perform(get("/tenants/current-resources/schema")).andExpect(status().isOk());
    http.perform(get("/tenants/current-resources").param("rootsOnly", "invalid")).andExpect(status().isBadRequest());
  }

  @Test
  void pickerAndCompatibilityEndpointsBindTheirExplicitNames() throws Exception {
    http.perform(get("/tenants/current-resources/picker").param("kind", "PAGE_TARGET")
        .param("childType", "PAGE").param("excludedId", "old").param("keyword", "审批")
        .param("selectedId", "selected")).andExpect(status().isOk());
    verify(catalog).picker(org.mockito.ArgumentMatchers.eq(TenantResourceCatalog.PickerKind.PAGE_TARGET),
        org.mockito.ArgumentMatchers.eq(ResourceType.PAGE), org.mockito.ArgumentMatchers.eq("old"),
        org.mockito.ArgumentMatchers.eq("审批"), org.mockito.ArgumentMatchers.eq("selected"), any());
    http.perform(get("/tenants/current-resources/resource-id")).andExpect(status().isOk());
    verify(catalog).get("resource-id");
    http.perform(post("/tenants/current-resources/sync").param("overwrite", "true")).andExpect(status().isOk());
    verify(catalog).synchronize(true);
    http.perform(get("/tenants/current-resources/sync/preview")).andExpect(status().isOk());
    verify(catalog).previewSynchronization(false);
  }
}
