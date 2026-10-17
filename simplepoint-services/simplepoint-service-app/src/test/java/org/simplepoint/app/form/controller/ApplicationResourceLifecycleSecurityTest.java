package org.simplepoint.app.form.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.service.ApplicationResourceLifecycleService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** Checks every lifecycle entry against real method-security interception. */
class ApplicationResourceLifecycleSecurityTest {
  @Configuration
  @EnableMethodSecurity
  static class SecurityConfiguration {}

  @Test
  void aRuntimeGrantDoesNotAuthorizeLifecycleAdministration() {
    var service = mock(ApplicationResourceLifecycleService.class);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(SecurityConfiguration.class);
      context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
          "events-test", java.util.Map.of("simplepoint.events.enabled", "true")));
      context.registerBean(ApplicationResourceLifecycleService.class, () -> service);
      context.registerBean(ApplicationResourceLifecycleController.class);
      context.refresh();
      var controller = context.getBean(ApplicationResourceLifecycleController.class);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
          "reader", "unused", AuthorityUtils.createAuthorityList("app.runtime.page.example")));
      assertThatThrownBy(() -> controller.inspect("app", "page")).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.offline("app", "page",
          new ApplicationResourceLifecycleController.OfflineRequest("op", 1))).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.retry("app", "page",
          new ApplicationResourceLifecycleController.RetryRequest("op", "retry"))).isInstanceOf(AccessDeniedException.class);
      verifyNoInteractions(service);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
          "publisher", "unused", AuthorityUtils.createAuthorityList("app.workbench.forms.publish")));
      controller.inspect("app", "page");
      verify(service).inspect("app", "page");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
