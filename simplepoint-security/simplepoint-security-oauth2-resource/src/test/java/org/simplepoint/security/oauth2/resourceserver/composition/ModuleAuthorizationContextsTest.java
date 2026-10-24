package org.simplepoint.security.oauth2.resourceserver.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.simplepoint.security.context.AuthorizationContextService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ModuleAuthorizationContextsTest {
  private final ModuleCatalog catalog =
      new ModuleCatalog(List.of(Alpha.class, Beta.class, Protocol.class, Missing.class));

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    org.simplepoint.core.RequestContextHolder.clearContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void modularAutoConfigurationDoesNotResolveAnUnownedPolicyClient() {
    new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                org.simplepoint.security.oauth2.resourceserver.config
                    .ResourceServerAutoConfiguration.class))
        .withUserConfiguration(WebSecurity.class)
        .withBean(
            org.simplepoint.platform.composition.ModulePlan.class,
            () -> catalog.plan(List.of("alpha")))
        .withBean(CacheService.class, () -> mock(CacheService.class))
        .withBean(
            com.fasterxml.jackson.databind.ObjectMapper.class,
            com.fasterxml.jackson.databind.ObjectMapper::new)
        .withBean(
            org.springframework.security.oauth2.jwt.JwtDecoder.class,
            () -> mock(org.springframework.security.oauth2.jwt.JwtDecoder.class))
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ModuleAuthorizationContexts.class)
                  .doesNotHaveBean(AuthorizationContextResolver.class)
                  .doesNotHaveBean(AuthorizationContextService.class);
            });
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
  static class WebSecurity {}

  @Test
  void moduleContributionIsMandatoryAndItsContractIsPartOfPlanning() {
    var alpha = catalog.plan(List.of("alpha"));
    assertThat(alpha.modules().getFirst().consumes().values())
        .contains(AuthorizationContextService.class);
    assertThatThrownBy(
            () ->
                new ModuleAuthorizationContexts(
                    catalog.plan(List.of("missing")), mock(CacheService.class), "/rpc"))
        .hasMessageContaining("MISSING_HTTP_AUTHORIZATION_CONTRIBUTION");
    new ModuleAuthorizationContexts(
        catalog.plan(List.of("protocol")), mock(CacheService.class), "/rpc");
  }

  @Test
  void eachStaticBoundaryUsesItsBoundClientIgnoringCallerAndSubjectHeaders() throws Exception {
    var filter =
        new ModuleAuthorizationContexts(
            catalog.plan(List.of("alpha", "beta")), mock(CacheService.class), "/rpc");
    var alpha = client("alpha.read");
    var beta = client("beta.read");
    prepare(filter, "alpha", alpha);
    prepare(filter, "beta", beta);
    invoke(filter, "/platform/alpha/records", "/platform", "alpha.read");
    verifyNoInteractions(beta);
    invoke(filter, "/beta/records", "", "beta.read");
    verify(alpha)
        .checkedCalculate(eq(null), eq("user-1"), eq(null), eq(Map.of("X-User-Id", "user-1")));
    verify(beta)
        .checkedCalculate(eq(null), eq("user-1"), eq(null), eq(Map.of("X-User-Id", "user-1")));
  }

  @Test
  void unboundOrDuplicateContributionsFailWithoutBorrowingAnotherClient() throws Exception {
    var filter =
        new ModuleAuthorizationContexts(
            catalog.plan(List.of("alpha", "beta")), mock(CacheService.class), "/rpc");
    var client = client("alpha.read");
    prepare(filter, "alpha", client);
    assertThatThrownBy(() -> prepare(filter, "alpha", client))
        .hasMessageContaining("DUPLICATE_HTTP_AUTHORIZATION");
    assertThatThrownBy(() -> prepare(filter, "missing", client))
        .hasMessageContaining("UNOWNED_HTTP_AUTHORIZATION");
    assertThatThrownBy(() -> invoke(filter, "/beta/records", "", "beta.read"))
        .hasMessageContaining("HTTP_AUTHORIZATION_NOT_PREPARED");
    verifyNoInteractions(client);
  }

  @Test
  void mountedInternalAndStaticPathsDoNotUseAnEndUserPolicyClient() throws Exception {
    var filter =
        new ModuleAuthorizationContexts(
            catalog.plan(List.of("alpha")), mock(CacheService.class), "/rpc");
    var client = client("alpha.read");
    prepare(filter, "alpha", client);
    for (String path :
        List.of(
            "/platform/alpha/internal/source",
            "/platform/alpha/mf/entry.js",
            "/platform/other/records")) {
      var request = request(path, "/platform");
      filter
          .filter()
          .doFilter(
              request,
              new MockHttpServletResponse(),
              (req, res) -> assertThat(AuthorizationContextHolder.getContext()).isNull());
    }
    verifyNoInteractions(client);
  }

  @Test
  void requestWithoutValidatedJwtCannotReachThePolicyService() throws Exception {
    var filter =
        new ModuleAuthorizationContexts(
            catalog.plan(List.of("alpha")), mock(CacheService.class), "/rpc");
    var client = client("alpha.read");
    prepare(filter, "alpha", client);
    var response = new MockHttpServletResponse();
    filter
        .filter()
        .doFilter(
            request("/alpha/records", ""),
            response,
            (req, res) -> {
              throw new AssertionError("unauthenticated");
            });
    assertThat(response.getStatus()).isEqualTo(401);
    verifyNoInteractions(client);
  }

  @Test
  void authenticatedSubjectResolverCannotResolveRawBearerHeaders() {
    var client = mock(AuthorizationContextService.class);
    var resolver =
        AuthorizationContextResolver.forAuthenticatedSubjects(mock(CacheService.class), client);
    assertThatThrownBy(() -> resolver.resolve(Map.of("Authorization", "Bearer unverified")))
        .isInstanceOf(
            org.springframework.security.authentication.AuthenticationServiceException.class);
    verifyNoInteractions(client);
  }

  private void invoke(
      ModuleAuthorizationContexts filter, String path, String contextPath, String permission)
      throws Exception {
    var token =
        new JwtAuthenticationToken(
            Jwt.withTokenValue("verified-token").header("alg", "PS256").subject("user-1").build(),
            List.of());
    SecurityContextHolder.getContext().setAuthentication(token);
    var response = new MockHttpServletResponse();
    filter
        .filter()
        .doFilter(
            request(path, contextPath),
            response,
            (req, res) -> {
              assertThat(AuthorizationContextHolder.getContext().getUserId()).isEqualTo("user-1");
              assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                  .extracting("authority")
                  .contains(permission);
            });
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(token);
  }

  private MockHttpServletRequest request(String path, String contextPath) {
    var request = new MockHttpServletRequest("GET", path);
    request.setContextPath(contextPath);
    request.addHeader("Authorization", "Bearer verified-token");
    request.addHeader("X-Module-Id", "another-module");
    request.addHeader("X-Service-Client", "administrator");
    request.addHeader("X-User-Id", "forged-user");
    return request;
  }

  private AuthorizationContextService client(String permission) {
    var client = mock(AuthorizationContextService.class);
    var context = new AuthorizationContext();
    context.setUserId("user-1");
    context.setResources(List.of(permission));
    when(client.checkedCalculate(any(), eq("user-1"), any(), anyMap())).thenReturn(context);
    return client;
  }

  private void prepare(
      ModuleAuthorizationContexts registry, String module, AuthorizationContextService client) {
    new ModuleAuthorizationInitializer(registry)
        .prepare(
            new ModuleInitializationContext() {
              public String moduleId() {
                return module;
              }

              public <T> T client(Class<T> contract) {
                assertThat(contract).isEqualTo(AuthorizationContextService.class);
                return contract.cast(client);
              }

              public byte[] resource(String path) {
                throw new AssertionError("No assets needed");
              }
            })
        .run();
  }

  @PlatformModule(
      id = "alpha",
      packages = "fixture.alpha",
      initializers = ModuleAuthorizationInitializer.class)
  @ModuleHttp(namespace = "/alpha")
  static class Alpha {}

  @PlatformModule(
      id = "beta",
      packages = "fixture.beta",
      initializers = ModuleAuthorizationInitializer.class)
  @ModuleHttp(namespace = "/beta")
  static class Beta {}

  @PlatformModule(id = "missing", packages = "fixture.missing")
  @ModuleHttp(namespace = "/missing")
  static class Missing {}

  @PlatformModule(id = "protocol", packages = "fixture.protocol")
  @ModuleHttp(namespace = "/protocol", scopedAuthorization = false)
  static class Protocol {}
}
