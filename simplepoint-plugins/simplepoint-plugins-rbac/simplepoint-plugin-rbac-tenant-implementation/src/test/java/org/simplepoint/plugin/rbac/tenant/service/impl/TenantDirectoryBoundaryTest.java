package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.invocation.ServiceRouterJsonCodec;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.TenantContextType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class TenantDirectoryBoundaryTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatedDirectoryUsesTheDomainAndDoesNotExportManagementOperations() throws Exception {
    var queryMethod = TenantDirectory.class.getMethod("getTenantsByUserId", String.class);
    var domain = mock(TenantService.class);
    var tenant = new NamedTenantVo("tenant", "企业空间", TenantContextType.ORGANIZATION);
    when(domain.getTenantsByUserId("user")).thenReturn(Set.of(tenant));
    new ApplicationContextRunner()
        .withUserConfiguration(Router.class)
        .withBean(TenantService.class, () -> domain)
        .withBean(TenantDirectoryImpl.class, () -> new TenantDirectoryImpl(domain))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(TenantService.class)).isSameAs(domain);
              var registry = context.getBean(LocalServiceRegistry.class);
              assertThat(registry.find("saas.tenant", "1")).isEmpty();
              assertThat(registry.find("saas.tenant", "2").orElseThrow().interfaceType())
                  .isEqualTo(TenantDirectory.class);
              authenticate("client_credentials");
              var directory = context.getBean(TenantDirectory.class);
              assertThat(directory.getTenantsByUserId("user")).containsExactly(tenant);
              var codec = new ServiceRouterJsonCodec(context.getBean(ObjectMapper.class));
              var dispatcher = context.getBean(ServiceInvocationDispatcher.class);
              var response =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "saas.tenant",
                                  "2",
                                  "getTenantsByUserId",
                                  List.of("user"),
                                  "query"))));
              assertThat(response.success()).isTrue();
              assertThat(codec.result(TenantDirectory.class, queryMethod, response.data()))
                  .isEqualTo(Set.of(tenant));
              response =
                  codec.decodeResponse(
                      dispatcher.dispatchJson(
                          codec.encode(
                              new RemoteRequest(
                                  "saas.tenant",
                                  "2",
                                  "removeAll",
                                  List.of(),
                                  "rejected-management"))));
              assertThat(response.success()).isFalse();
              verify(domain, never()).removeAll();
            });
  }

  @Test
  void ordinaryAndUserGrantTokensCannotImpersonateServices() {
    var domain = mock(TenantService.class);
    var directory = new TenantDirectoryImpl(domain);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken("workflow", null, "SCOPE_service-router.invoke"));
    assertThatThrownBy(() -> directory.getTenantsByUserId("user"))
        .isInstanceOf(AccessDeniedException.class);
    authenticate("authorization_code");
    assertThatThrownBy(() -> directory.resolveActiveMemberIds("tenant", List.of("user")))
        .isInstanceOf(AccessDeniedException.class);
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () -> directory.resolveActiveRoleMemberIds("tenant", List.of("role"), null, 10))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(domain);
  }

  @Test
  void rejectsUnboundedBatchesBeforeCallingTheDomain() {
    var domain = mock(TenantService.class);
    var directory = new TenantDirectoryImpl(domain);
    authenticate("client_credentials");
    assertThatThrownBy(
            () -> directory.resolveActiveMemberIds("tenant", Collections.nCopies(51, "duplicate")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> directory.resolveActiveRoleAuthorities("tenant", List.of("x".repeat(101))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> directory.resolveActiveRoleMemberIds("tenant", List.of("role"), null, 201))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> directory.getTenantsByUserId(" "))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(domain);
    String role = "𠮷".repeat(100);
    directory.resolveActiveRoleAuthorities("tenant", List.of(role));
    verify(domain).resolveActiveRoleAuthorities("tenant", List.of(role));
  }

  private void authenticate(String grant) {
    var principal =
        new DefaultOAuth2AuthenticatedPrincipal(
            "display-name", Map.of("client_id", "workflow", "grant_type", grant), List.of());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(principal, null, "SCOPE_service-router.invoke"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableServiceRouter(basePackages = "org.simplepoint.plugin.rbac.tenant.api.service")
  static class Router {}
}
