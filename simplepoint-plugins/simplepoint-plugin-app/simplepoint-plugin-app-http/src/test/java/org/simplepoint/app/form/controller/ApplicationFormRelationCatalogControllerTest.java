package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetMetadata;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetPage;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRelationCatalogService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationFormRelationCatalogControllerTest {
  private static final String ROUTE = "/applications/source-app/forms/source-form/relation-targets";
  private final ApplicationFormRelationCatalogService catalog =
      mock(ApplicationFormRelationCatalogService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var proxy = new ProxyFactory(new ApplicationFormRelationCatalogController(catalog));
    proxy.setProxyTargetClass(true);
    proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new ApplicationFormExceptionHandler())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user", "unused", List.of(new SimpleGrantedAuthority("app.workbench.view"))));
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void searchUsesBoundedDefaultsAndTheOwningSourceRoute() throws Exception {
    when(catalog.search("source-app", "source-form", "日本", 1, 50))
        .thenReturn(new FormRelationTargetPage(1, 50, 61, List.of()));
    mvc.perform(get(ROUTE).param("keyword", "日本").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(61))
        .andExpect(jsonPath("$.size").value(50));
  }

  @Test
  void hydrationAndMetadataNeverTakeCallerTenantOrPhysicalPlacement() throws Exception {
    when(catalog.resolve("source-app", "source-form", List.of("resource"))).thenReturn(List.of());
    mvc.perform(
            post(ROUTE + "/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\"resource\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    var target = new FormRelationTarget("target-app", "target-form", "resource");
    when(catalog.describe("source-app", "source-form", target))
        .thenReturn(new FormRelationTargetMetadata(target, "page", 2, List.of()));
    mvc.perform(
            post(ROUTE + "/describe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"applicationId\":\"target-app\",\"formId\":\"target-form\","
                        + "\"accessResourceId\":\"resource\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.formVersion").value(2))
        .andExpect(jsonPath("$.physicalModelId").doesNotExist());
  }

  @Test
  void inverseSourceUsesTheDesignerOwnerAndSeparateSourceIdentity() throws Exception {
    var source = new FormRelationTarget("sales", "trades", "trade-resource");
    when(catalog.inverseSource("source-app", "source-form", source))
        .thenReturn(
            new org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata(
                source, 3, List.of()));
    mvc.perform(
            post(ROUTE + "/inverse-source")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"applicationId\":\"sales\",\"formId\":\"trades\","
                        + "\"accessResourceId\":\"trade-resource\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.formVersion").value(3))
        .andExpect(jsonPath("$.source.formId").value("trades"));
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () ->
                mvc.perform(
                    post(ROUTE + "/inverse-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"applicationId\":\"sales\",\"formId\":\"trades\","
                                + "\"accessResourceId\":\"trade-resource\"}")))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
  }

  @Test
  void normalBusinessRecordAccessDoesNotGrantDesignerDiscovery() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user", "unused", List.of(new SimpleGrantedAuthority("app.runtime.page.example"))));
    assertThatThrownBy(() -> mvc.perform(get(ROUTE)))
        .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(catalog);
  }
}
