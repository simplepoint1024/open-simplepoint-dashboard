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
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingObservation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingView;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceMappingService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationFormReferenceMappingControllerTest {
  private static final String ROUTE =
      "/runtime/applications/app/pages/page/references/customer/mapping";
  private static final String TARGET = "11111111-1111-1111-1111-111111111111";
  private final ApplicationFormReferenceMappingService service =
      mock(ApplicationFormReferenceMappingService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var proxy = new ProxyFactory(new ApplicationFormReferenceMappingController(service));
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
    when(service.observe(eq("app"), eq("page"), eq("customer"), any()))
        .thenReturn(
            new FormReferenceMappingView(
                new FormReferenceMappingObservation("a".repeat(64), TARGET, 3L),
                Map.of("amount", new FormValue(FormValueKind.DECIMAL, "9007199254740993.12")),
                Map.of("memo", new FormValue(FormValueKind.STRING, "客户・日本"))));
    mvc.perform(
            post(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"runtime":{"formId":"form","formVersion":2},
                     "targetRecordId":"11111111-1111-1111-1111-111111111111",
                     "applyDefaultMappingIds":["memo"]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snapshots.amount.value").value("9007199254740993.12"))
        .andExpect(jsonPath("$.defaults.memo.value").value("客户・日本"));
    verify(service)
        .observe(
            "app",
            "page",
            "customer",
            new FormReferenceMappingQuery(
                new ApplicationFormRuntimeIdentity("form", 2),
                null,
                null,
                TARGET,
                List.of("memo")));
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
}
