package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.PreviewFormCalculationCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.simplepoint.plugin.app.form.service.ApplicationFormCalculationServiceImpl;
import org.simplepoint.plugin.app.form.service.DefaultFormComponentRegistry;
import org.simplepoint.plugin.app.form.service.FormDocumentValidator;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises HTTP parsing, method authority, actual formula compilation and exact typed results. */
class ApplicationFormCalculationControllerTest {
  private static final String ROUTE = "/applications/app-a/forms/form-a/calculations";
  private final ObjectMapper json = new ObjectMapper();
  private ApplicationFormService forms;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    forms = mock(ApplicationFormService.class);
    var service =
        new ApplicationFormCalculationServiceImpl(
            forms, new FormDocumentValidator(new DefaultFormComponentRegistry()));
    var proxy = new ProxyFactory(new ApplicationFormCalculationController(service));
    proxy.setProxyTargetClass(true);
    proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    mvc =
        MockMvcBuilders.standaloneSetup(proxy.getProxy())
            .setControllerAdvice(new ApplicationFormExceptionHandler())
            .build();
    authenticate("app.workbench.forms.edit");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void catalogContainsVersionedMetadataAndNoExecutableBodies() throws Exception {
    var response =
        mvc.perform(get(ROUTE + "/catalog"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.languageVersion").value("1"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var functions = json.readTree(response).path("functions");
    assertThat(functions.size()).isEqualTo(63);
    assertThat(response)
        .contains("ROUND", "minArguments", "parameters")
        .doesNotContain("body", "org.simplepoint");
    verify(forms).get("app-a", "form-a");
  }

  @Test
  void previewComputesUnsavedDetailValuesAndExactTotalsWithoutReturningSources() throws Exception {
    var command =
        command(
            "SUM([lines.amount])",
            Map.of("rate", "0.95"),
            Map.of(
                "lines",
                List.of(
                    Map.of("price", "10.01", "quantity", "3"),
                    Map.of("price", "8.25", "quantity", "2"))));
    mvc.perform(
            post(ROUTE + "/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(command)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.values.total").value("44.21"))
        .andExpect(jsonPath("$.values.rate").doesNotExist())
        .andExpect(jsonPath("$.details.lines[0].amount").value("28.53"))
        .andExpect(jsonPath("$.details.lines[1].amount").value("15.68"))
        .andExpect(jsonPath("$.details.lines[0].price").doesNotExist());
    verify(forms).get("app-a", "form-a");
  }

  @Test
  void readAndPublishAuthorityDoNotImplyFormulaTrialAuthority() throws Exception {
    for (String authority : List.of("app.workbench.view", "app.workbench.forms.publish")) {
      authenticate(authority);
      assertThatThrownBy(() -> mvc.perform(get(ROUTE + "/catalog")))
          .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      var body = json.writeValueAsBytes(command("1", Map.of(), Map.of("lines", List.of())));
      assertThatThrownBy(
              () ->
                  mvc.perform(
                      post(ROUTE + "/preview")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(body)))
          .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    verifyNoInteractions(forms);
  }

  @Test
  void ownershipIsResolvedBeforeCompilingOrEvaluatingSamples() throws Exception {
    when(forms.get("app-a", "form-a"))
        .thenThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.FORM_NOT_FOUND, "Form is unavailable"));
    mvc.perform(
            post(ROUTE + "/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        command("UNKNOWN_FUNCTION()", Map.of(), Map.of("lines", List.of())))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("FORM_NOT_FOUND"));
  }

  @Test
  void failuresHaveNamespacedDiagnosticsWithoutEchoingInputValues() throws Exception {
    String secret = "unrelated-private-source";
    var command = command("1/0", Map.of("rate", secret), Map.of("lines", List.of()));
    var response =
        mvc.perform(
                post(ROUTE + "/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(command)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("RECORD_INVALID"))
            .andExpect(jsonPath("$.messageKey").value("app.error.RECORD_INVALID"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(secret);
  }

  @Test
  void trialExposesValueFreeStructuredFormulaLocation() throws Exception {
    mvc.perform(
            post(ROUTE + "/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        command("1/0", Map.of("rate", "1"), Map.of("lines", List.of())))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.messageKey").value("app.error.RECORD_INVALID"))
        .andExpect(jsonPath("$.calculation.code").value("DIV_ZERO"))
        .andExpect(jsonPath("$.calculation.scope").value("parent"))
        .andExpect(jsonPath("$.calculation.fieldId").value("total"));
  }

  @Test
  void trialRejectsForgedOutputsAndInvalidGraphs() throws Exception {
    mvc.perform(
            post(ROUTE + "/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        command("1", Map.of("total", "900"), Map.of("lines", List.of())))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RECORD_INVALID"));
    mvc.perform(
            post(ROUTE + "/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(
                        command("[unknown]", Map.of(), Map.of("lines", List.of())))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID"));
  }

  @Test
  void commandBoundsRowsAndPreservesNullValues() {
    assertThatThrownBy(
            () -> command("1", Map.of(), Map.of("lines", Collections.nCopies(41, Map.of()))))
        .isInstanceOf(IllegalArgumentException.class);
    var command = command("1", Collections.singletonMap("rate", null), Map.of("lines", List.of()));
    assertThat(command.values()).containsEntry("rate", null);
    assertThatThrownBy(() -> command.values().put("rate", "9"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static void authenticate(String authority) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "designer", "unused", List.of(new SimpleGrantedAuthority(authority))));
  }

  private static PreviewFormCalculationCommand command(
      String expression,
      Map<String, Object> values,
      Map<String, List<Map<String, Object>>> details) {
    var root =
        new FormComponentDefinition(
            "root",
            "layout.form",
            "1",
            null,
            24,
            null,
            null,
            null,
            null,
            false,
            Map.of(),
            List.of());
    var document =
        new ApplicationFormDocument(
            "1",
            List.of(field("rate", null), field("total", expression)),
            List.of(),
            List.of(
                new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)),
            List.of(),
            List.of(
                new FormDetailDefinition(
                    "lines",
                    "expense_lines",
                    "费用明细",
                    List.of(
                        field("price", null),
                        field("quantity", null),
                        field("amount", "ROUND([price]*[quantity]*[parent.rate],2)")),
                    List.of())));
    return new PreviewFormCalculationCommand(document, values, details);
  }

  private static FormFieldDefinition field(String id, String expression) {
    return new FormFieldDefinition(
        id,
        "column_" + id,
        id,
        id,
        FormFieldType.DECIMAL,
        expression == null ? FormStorageMode.STORED : FormStorageMode.COMPUTED,
        false,
        false,
        null,
        18,
        2,
        null,
        List.of(),
        null,
        null,
        null,
        expression == null ? null : new FormCalculation("1", expression));
  }
}
