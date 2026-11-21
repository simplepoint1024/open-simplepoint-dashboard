package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveView;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceLiveService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationFormReferenceLiveControllerTest {
  private static final String ROUTE =
      "/runtime/applications/app/pages/page/references/customer/live";
  private static final String TARGET = "11111111-1111-1111-1111-111111111111";
  private final ApplicationFormReferenceLiveService service =
      mock(ApplicationFormReferenceLiveService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var proxy = new ProxyFactory(new ApplicationFormReferenceLiveController(service));
    proxy.setProxyTargetClass(true);
    proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new ApplicationFormExceptionHandler())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("user", "unused", List.of()));
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void delegatesOnlyRouteAndTypedIntentAndPreservesCanonicalValues() throws Exception {
    when(service.read(eq("app"), eq("page"), eq("customer"), any()))
        .thenReturn(
            new FormReferenceLiveView(
                TARGET,
                3L,
                Map.of(
                    "amount",
                    new FormValue(FormValueKind.DECIMAL, "9007199254740993.12"),
                    "memo",
                    new FormValue(FormValueKind.STRING, "客户・日本"))));
    mvc.perform(
            post(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"runtime":{"formId":"form","formVersion":2},
                     "targetRecordId":"11111111-1111-1111-1111-111111111111"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.values.amount.value").value("9007199254740993.12"))
        .andExpect(jsonPath("$.values.memo.value").value("客户・日本"));
    verify(service)
        .read(
            "app",
            "page",
            "customer",
            new FormReferenceLiveQuery(new ApplicationFormRuntimeIdentity("form", 2), TARGET));
  }

  @Test
  void missingSelectionIsNotAnImplicitClear() throws Exception {
    mvc.perform(
            post(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"runtime":{"formId":"form","formVersion":2}}
                    """))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void unauthenticatedRequestsCannotEnterTheDomain() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () ->
                mvc.perform(
                    post(ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"runtime":{"formId":"form","formVersion":2},"targetRecordId":null}
                            """)))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
    verifyNoInteractions(service);
  }

  @Test
  void delegatesBoundedBatchWithoutExpandingItIntoSingleRequests() throws Exception {
    when(service.readBatch(eq("app"), eq("page"), eq("customer"), any()))
        .thenReturn(
            List.of(
                new FormReferenceLiveView(
                    TARGET, 4L, Map.of("name", new FormValue(FormValueKind.STRING, "客户・日本")))));
    mvc.perform(
            post(ROUTE + "/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"runtime":{"formId":"form","formVersion":2},
                     "targetRecordIds":["11111111-1111-1111-1111-111111111111"]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].values.name.value").value("客户・日本"));
    verify(service)
        .readBatch(
            "app",
            "page",
            "customer",
            new org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery(
                new ApplicationFormRuntimeIdentity("form", 2), List.of(TARGET)));
    verify(service, org.mockito.Mockito.never()).read(any(), any(), any(), any());
  }
}
