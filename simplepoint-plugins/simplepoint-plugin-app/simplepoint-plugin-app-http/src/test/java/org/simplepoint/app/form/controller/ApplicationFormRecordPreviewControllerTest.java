package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPreviewView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordSchemaService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRecordService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract only; physical calculation and rollback have separate database fixtures. */
class ApplicationFormRecordPreviewControllerTest {
  private static final String ROUTE = "/runtime/applications/app/pages/page/records/preview";
  private static final String BODY =
      "{\"runtime\":{\"formId\":\"form\",\"formVersion\":1},\"state\":\"DRAFT\",\"values\":{\"base\":\"4503599627370496.56\"}}\n";
  private final ApplicationFormRecordService records = mock(ApplicationFormRecordService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    var proxy =
        new ProxyFactory(
            new ApplicationFormRecordController(
                records, mock(ApplicationFormRecordSchemaService.class)));
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
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void preservesExactValuesAndOrderedDetailProjections() throws Exception {
    when(records.preview(eq("app"), eq("page"), any()))
        .thenReturn(
            new ApplicationFormRecordPreviewView(
                new ApplicationFormRuntimeIdentity("form", 1),
                Map.of("total", "9007199254740993.12"),
                Map.of("lines", List.of(Map.of("amount", "0.01"), Map.of("amount", "2.00")))));
    mvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.values.total").value("9007199254740993.12"))
        .andExpect(jsonPath("$.details.lines[0].amount").value("0.01"))
        .andExpect(jsonPath("$.details.lines[1].amount").value("2.00"));
  }

  @Test
  void rejectsAnIncompleteOptimisticIdentityBeforeCallingTheService() throws Exception {
    mvc.perform(
            post(ROUTE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"state\"", "\"recordId\":\"record\",\"state\"")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(records);
  }

  @Test
  void unauthenticatedRequestsCannotReachThePreviewService() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () -> mvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(BODY)))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
    verifyNoInteractions(records);
  }
}
