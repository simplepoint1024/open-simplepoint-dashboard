package org.simplepoint.workflow.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.workflow.service.WorkflowNumericLiteralPreview;
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

/** Checks real HTTP serialization, narrow validation and the actual method-security proxy. */
class WorkflowLiteralPreviewControllerTest {

  @Test
  void httpPreservesNumericLookingIdentitiesAsJsonStringsAndRejectsGeneralExpressions() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new WorkflowLiteralPreviewController(new WorkflowNumericLiteralPreview()))
        .setControllerAdvice(new WorkflowExceptionHandler()).build();
    mvc.perform(get("/definitions/literal-preview").param("expression", "${1e2}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("100.0"));
    mvc.perform(get("/definitions/literal-preview").param("expression", "${9007199254740993}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("9007199254740993"));
    for (String expression : List.of("${user}", "${task.getClass()}", "${1 + 2}", "${1}${2}", "${" + "1".repeat(500) + "}")) {
      mvc.perform(get("/definitions/literal-preview").param("expression", expression))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WORKFLOW_VALIDATION"));
    }
    mvc.perform(get("/definitions/literal-preview")).andExpect(status().isBadRequest());
  }

  @Test
  void onlyDefinitionReadersOrAdministratorsCanInvokeThePreview() {
    try (var context = new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
      var controller = context.getBean(WorkflowLiteralPreviewController.class);
      SecurityContextHolder.clearContext();
      assertThatThrownBy(() -> controller.preview("${1e2}"))
          .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
      for (String authority : List.of("workflow.definitions.edit", "workflow.tasks.view")) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-reader", null,
            List.of(new SimpleGrantedAuthority(authority))));
        assertThatThrownBy(() -> controller.preview("${1e2}")).isInstanceOf(AccessDeniedException.class);
      }
      for (String authority : List.of("workflow.definitions.view", "ROLE_Administrator")) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-reader", null,
            List.of(new SimpleGrantedAuthority(authority))));
        assertThat(controller.preview("${1e2}").getBody()).isEqualTo(new WorkflowLiteralPreviewController.LiteralPreview("100.0"));
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class SecurityConfiguration {

    @Bean
    WorkflowLiteralPreviewController controller() {
      return new WorkflowLiteralPreviewController(new WorkflowNumericLiteralPreview());
    }
  }
}
