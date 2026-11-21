package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceDetail;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferencePage;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationFormReferenceControllerTest {
  private static final org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity
      RUNTIME =
          new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity("form", 1);
  private static final String ROUTE = "/runtime/applications/app/pages/page/references/customer";
  private final ApplicationFormReferenceService references =
      mock(ApplicationFormReferenceService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var proxy = new ProxyFactory(new ApplicationFormReferenceController(references));
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
  void preservesAuthorizedUnicodeLabelsAndExactValues() throws Exception {
    when(references.search("app", "page", "customer", "日本", 1, 50, RUNTIME))
        .thenReturn(
            new FormReferencePage(
                1,
                50,
                61,
                List.of(
                    new FormReferenceOption(
                        "record", 4, "客户日本", Map.of("amount", "9007199254740993.12")))));
    mvc.perform(
            get(ROUTE)
                .param("formId", "form")
                .param("formVersion", "1")
                .param("keyword", "日本")
                .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(61))
        .andExpect(jsonPath("$.records[0].label").value("客户日本"))
        .andExpect(jsonPath("$.records[0].values.amount").value("9007199254740993.12"));
  }

  @Test
  void hydrationUsesOnlyTheRouteAndBoundedIdentityRequest() throws Exception {
    when(references.resolve("app", "page", "customer", List.of("record"), RUNTIME))
        .thenReturn(List.of());
    mvc.perform(
            post(ROUTE + "/resolve")
                .param("formId", "form")
                .param("formVersion", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\"record\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    verify(references).resolve("app", "page", "customer", List.of("record"), RUNTIME);
  }

  @Test
  void drillThroughPreservesTheResolvedFormAndRecordWithoutClientTargetParameters()
      throws Exception {
    var form =
        new ApplicationFormRuntimeView(
            "customers",
            "customer-page",
            "客户",
            "customer-form",
            "客户",
            4,
            "main",
            "customer-resource",
            null,
            null);
    when(references.detail("app", "page", "customer", "record", RUNTIME))
        .thenReturn(
            new FormReferenceDetail(
                form,
                new ApplicationFormRecordView(
                    "record",
                    7,
                    FormRecordState.SUBMITTED,
                    Map.of("amount", "9007199254740993.12"))));
    mvc.perform(get(ROUTE + "/record").param("formId", "form").param("formVersion", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.form.applicationId").value("customers"))
        .andExpect(jsonPath("$.form.formVersion").value(4))
        .andExpect(jsonPath("$.record.version").value(7))
        .andExpect(jsonPath("$.record.values.amount").value("9007199254740993.12"));
    mvc.perform(get(ROUTE + "/record")).andExpect(status().isBadRequest());
    verify(references).detail("app", "page", "customer", "record", RUNTIME);
    verifyNoMoreInteractions(references);
  }

  @Test
  void contextualSearchKeepsTypedValuesInTheBodyAndRejectsOversizedInputsBeforeDelegating()
      throws Exception {
    var context =
        new FormReferenceSearchContext(
            Map.of("number", new FormValue(FormValueKind.INTEGER, "9007199254740993")),
            Map.of("region", new FormValue(FormValueKind.STRING, "東京😀")));
    when(references.searchContext("app", "page", "customer", "日本", 1, 50, RUNTIME, context))
        .thenReturn(new FormReferencePage(1, 50, 61, List.of()));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    mvc.perform(
            post(ROUTE + "/search")
                .param("formId", "form")
                .param("formVersion", "1")
                .param("keyword", "日本")
                .param("page", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(context)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(61));
    verify(references).searchContext("app", "page", "customer", "日本", 1, 50, RUNTIME, context);
    clearInvocations(references);
    for (String body :
        List.of(
            "{\"fields\":{\"field\":{\"kind\":\"STRING\",\"value\":\"" + "x".repeat(2001) + "\"}}}",
            "{\"fields\":{\"../../field\":{\"kind\":\"STRING\",\"value\":\"x\"}}}",
            "{\"fields\":{\"field\":null}}")) {
      mvc.perform(
              post(ROUTE + "/search")
                  .param("formId", "form")
                  .param("formVersion", "1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(references);
  }

  @Test
  void authenticationIsRequiredForBothDirectoryOperations() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () ->
                mvc.perform(
                    post(ROUTE + "/search")
                        .param("formId", "form")
                        .param("formVersion", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
    assertThatThrownBy(
            () ->
                mvc.perform(
                    get(ROUTE + "/record").param("formId", "form").param("formVersion", "1")))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
    assertThatThrownBy(
            () -> mvc.perform(get(ROUTE).param("formId", "form").param("formVersion", "1")))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
    assertThatThrownBy(
            () ->
                mvc.perform(
                    post(ROUTE + "/resolve")
                        .param("formId", "form")
                        .param("formVersion", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]")))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
    verifyNoInteractions(references);
  }
}
