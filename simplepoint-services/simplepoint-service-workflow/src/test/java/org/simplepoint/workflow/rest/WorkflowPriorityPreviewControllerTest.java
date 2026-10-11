package org.simplepoint.workflow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.service.WorkflowPriorityLiteralPreview;
import org.simplepoint.plugin.workflow.service.WorkflowPriorityLiteralPreview.PriorityPreview;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Uses genuine serialization and method-security interception for the stateless preview. */
class WorkflowPriorityPreviewControllerTest {

  @Test
  void httpReturnsNativeValuesOrTranslatedIssueCodesWithoutEvaluatingVariableExpressions() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new WorkflowPriorityPreviewController(new WorkflowPriorityLiteralPreview()))
        .setControllerAdvice(new WorkflowExceptionHandler()).build();
    mvc.perform(get("/definitions/priority-preview").param("value", "${0.5}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("0"));
    mvc.perform(get("/definitions/priority-preview").param("value", "${9007199254740993}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("1"));
    mvc.perform(get("/definitions/priority-preview").param("value", "${true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.issue").value("PRIORITY_TYPE"));
    mvc.perform(get("/definitions/priority-preview").param("value", "${'1e2'}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.issue").value("PRIORITY_INTEGER"));
    for (String source : List.of("${user}", "${execution}", "${foo()}", "${1 + 2}", "${1}${2}", "${" + "1".repeat(500) + "}")) {
      mvc.perform(get("/definitions/priority-preview").param("value", source))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WORKFLOW_VALIDATION"));
    }
  }

  @Test
  void onlyDefinitionReadersAndAdministratorsCanInvokeNativePriorityPreview() {
    try (var context = new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
      var controller = context.getBean(WorkflowPriorityPreviewController.class);
      SecurityContextHolder.clearContext();
      assertThatThrownBy(() -> controller.preview("${0.5}"))
          .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-user", null,
          List.of(new SimpleGrantedAuthority("workflow.definitions.edit"))));
      assertThatThrownBy(() -> controller.preview("${0.5}")).isInstanceOf(AccessDeniedException.class);
      for (String authority : List.of("workflow.definitions.view", "ROLE_Administrator")) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-reader", null,
            List.of(new SimpleGrantedAuthority(authority))));
        assertThat(controller.preview("${0.5}").getBody()).isEqualTo(new PriorityPreview("0", null));
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class SecurityConfiguration {

    @Bean
    WorkflowPriorityPreviewController controller() {
      return new WorkflowPriorityPreviewController(new WorkflowPriorityLiteralPreview());
    }
  }
}
