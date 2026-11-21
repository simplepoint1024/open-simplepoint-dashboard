package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormInverseRecordService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationFormInverseRecordControllerTest {
  private static final String PARENT = "aaaaaaaa-1111-2222-3333-000000000001";
  private static final String ROW = "aaaaaaaa-1111-2222-3333-000000000002";
  private static final String ROUTE =
      "/runtime/applications/app/pages/page/records/" + PARENT + "/relations/trades/records";
  private static final ApplicationFormRuntimeIdentity SOURCE =
      new ApplicationFormRuntimeIdentity("trade-form", 3);
  private static final FormInverseViewIdentity VIEW =
      new FormInverseViewIdentity(
          new ApplicationFormRuntimeIdentity("customer-form", 7), "trades", PARENT);
  private final ApplicationFormInverseRecordService records =
      mock(ApplicationFormInverseRecordService.class);
  private final org.simplepoint.plugin.app.form.service.FormInverseSubmissionService submissions =
      mock(org.simplepoint.plugin.app.form.service.FormInverseSubmissionService.class);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var proxy = new ProxyFactory(new ApplicationFormInverseRecordController(records));
    proxy.setProxyTargetClass(true);
    proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    var catalog = new ProxyFactory(new ApplicationFormInverseCatalogController(records));
    catalog.setProxyTargetClass(true);
    catalog.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    var submissionController =
        new ProxyFactory(new ApplicationFormInverseSubmissionController(submissions));
    submissionController.setProxyTargetClass(true);
    submissionController.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    mvc =
        MockMvcBuilders.standaloneSetup(
                proxy.getProxy(), catalog.getProxy(), submissionController.getProxy())
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
  void scopedSubmissionRoutesKeepObservedParentAndSourceVersionsSeparate() throws Exception {
    String route = ROUTE.substring(0, ROUTE.length() - "/records".length()) + "/submissions";
    mvc.perform(parent(get(route + "/definitions"))).andExpect(status().isOk());
    verify(submissions).definitions("app", "page", VIEW, "", 0, 20);
    mvc.perform(
            parent(get(route + "/preparation"))
                .param("definitionId", "approval")
                .param("definitionVersion", "2"))
        .andExpect(status().isOk());
    verify(submissions).prepare("app", "page", VIEW, "approval", 2);
    mvc.perform(
            parent(post(route))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
{"runtime":{"formId":"trade-form","formVersion":3},"idempotencyKey":"approval-1",
 "recordId":"aaaaaaaa-1111-2222-3333-000000000002","expectedVersion":4,
 "definitionId":"approval","definitionVersion":2,"title":"交易审批","values":{}}
"""))
        .andExpect(status().isOk());
    var command =
        org.mockito.ArgumentCaptor.forClass(
            org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand.class);
    verify(submissions)
        .submit(
            org.mockito.ArgumentMatchers.eq("app"),
            org.mockito.ArgumentMatchers.eq("page"),
            org.mockito.ArgumentMatchers.eq(VIEW),
            command.capture());
    org.assertj.core.api.Assertions.assertThat(command.getValue().runtime()).isEqualTo(SOURCE);
    org.assertj.core.api.Assertions.assertThat(command.getValue().recordId()).isEqualTo(ROW);
    mvc.perform(parent(get(route + "/accepted"))).andExpect(status().isOk());
    verify(submissions).get("app", "page", VIEW, "accepted");
  }

  @Test
  void submissionRejectsMissingParentPublicationBeforeCallingDomain() throws Exception {
    String route = ROUTE.substring(0, ROUTE.length() - "/records".length()) + "/submissions";
    mvc.perform(get(route + "/definitions")).andExpect(status().isBadRequest());
    verifyNoInteractions(submissions);
  }

  @Test
  void catalogAndDescriptorKeepTheSameObservedParentIdentity() throws Exception {
    when(records.views("app", "page", VIEW.runtime(), PARENT))
        .thenReturn(
            List.of(
                new org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewSummary(
                    "trades", "交易", null)));
    String catalog = ROUTE.substring(0, ROUTE.indexOf("/relations/")) + "/relations";
    mvc.perform(parent(get(catalog)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].viewId").value("trades"));
    mvc.perform(parent(get(ROUTE + "/runtime"))).andExpect(status().isOk());
    verify(records).views("app", "page", VIEW.runtime(), PARENT);
    verify(records).describe("app", "page", VIEW);
  }

  @Test
  void everyReadRetainsParentEntryIdentityAndSourceRecordIdentity() throws Exception {
    when(records.schema("app", "page", VIEW)).thenReturn(Map.of("buttons", List.of()));
    when(records.get("app", "page", VIEW, ROW))
        .thenReturn(
            new ApplicationFormRecordView(ROW, 2, FormRecordState.DRAFT, Map.of("name", "交易 日本")));
    mvc.perform(parent(get(ROUTE + "/schema"))).andExpect(status().isOk());
    mvc.perform(parent(get(ROUTE + "/" + ROW)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.values.name").value("交易 日本"));
    mvc.perform(
            parent(post(ROUTE + "/query"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"page\":1,\"size\":50}"))
        .andExpect(status().isOk());
    verify(records).schema("app", "page", VIEW);
    verify(records).get("app", "page", VIEW, ROW);
    verify(records)
        .page(
            "app",
            "page",
            VIEW,
            new ApplicationFormRecordPageQuery(1, 50, List.of(), null, List.of()));
  }

  @Test
  void writesPreserveSeparateParentAndSourceVersionsAndUnlinkIntent() throws Exception {
    String body =
        """
        {"runtime":{"formId":"trade-form","formVersion":3},"idempotencyKey":"operation",
         "expectedVersion":2,"state":"DRAFT","values":{"name":"交易"}}
        """;
    mvc.perform(parent(post(ROUTE)).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    mvc.perform(
            parent(put(ROUTE + "/" + ROW)).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    mvc.perform(
            parent(post(ROUTE + "/" + ROW + "/unlink"))
                .param("sourceRowId", ROW)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            parent(delete(ROUTE + "/" + ROW))
                .param("expectedVersion", "2")
                .param("formId", SOURCE.formId())
                .param("formVersion", "3")
                .param("idempotencyKey", "delete"))
        .andExpect(status().isOk());
    var update =
        new UpdateApplicationFormRecordCommand(
            SOURCE, 2, "operation", FormRecordState.DRAFT, Map.of("name", "交易"));
    verify(records)
        .create(
            "app",
            "page",
            VIEW,
            new CreateApplicationFormRecordCommand(
                SOURCE, "operation", FormRecordState.DRAFT, Map.of("name", "交易")));
    verify(records).update("app", "page", VIEW, ROW, update);
    verify(records).unlink("app", "page", VIEW, ROW, update, ROW);
    verify(records).delete("app", "page", VIEW, ROW, 2, SOURCE, "delete");
  }

  @Test
  void missingObservedParentVersionNeverCallsTheDomain() throws Exception {
    mvc.perform(get(ROUTE + "/schema").param("parentFormId", "customer-form"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(records);
  }

  @Test
  void malformedParentIdentityHasStablePublicErrorAndCannotReachTheDomain() throws Exception {
    mvc.perform(parent(get(ROUTE.replace(PARENT, "1-1-1-1-1") + "/schema")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RECORD_INVALID"))
        .andExpect(jsonPath("$.messageKey").value("app.error.RECORD_INVALID"));
    verifyNoInteractions(records);
  }

  @Test
  void anonymousRequestCannotEnterTheDomain() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> mvc.perform(parent(get(ROUTE + "/schema"))))
        .hasRootCauseInstanceOf(
            org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
                .class);
    verifyNoInteractions(records);
  }

  private static MockHttpServletRequestBuilder parent(MockHttpServletRequestBuilder request) {
    return request.param("parentFormId", "customer-form").param("parentFormVersion", "7");
  }
}
