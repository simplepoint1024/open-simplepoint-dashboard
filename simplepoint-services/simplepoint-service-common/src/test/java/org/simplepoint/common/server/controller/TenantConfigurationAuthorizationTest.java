package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.rest.controller.TenantController;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Actual method-security proxy; target owner/admin eligibility is separately exercised through real HTTP/service tests. */
class TenantConfigurationAuthorizationTest {
  @AfterEach void cleanup() {SecurityContextHolder.clearContext();}

  @Test void anonymousCannotProbeCapabilitiesAndAuthenticatedReadersStillReceiveTheServicePolicy() {
    try (var context = new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
      var controller = context.getBean(TenantController.class);
      var service = context.getBean(TenantService.class);
      SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymous",
          List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
      assertThrows(AccessDeniedException.class, () -> controller.userManagementCapabilities("tenant"));
      verifyNoInteractions(service);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", null, List.of()));
      when(service.userManagementCapabilities("tenant")).thenReturn(Map.of("canConfigureMembers", false));
      assertEquals(Map.of("canConfigureMembers", false), controller.userManagementCapabilities("tenant").getBody());
      verify(service).userManagementCapabilities("tenant");
    }
  }

  @Configuration(proxyBeanMethods = false) @EnableMethodSecurity(proxyTargetClass = true)
  static class SecurityConfiguration {
    @Bean TenantService tenantService() {return mock(TenantService.class);}
    @Bean TenantController tenantController(TenantService service) {return new TenantController(service);}
  }
}
