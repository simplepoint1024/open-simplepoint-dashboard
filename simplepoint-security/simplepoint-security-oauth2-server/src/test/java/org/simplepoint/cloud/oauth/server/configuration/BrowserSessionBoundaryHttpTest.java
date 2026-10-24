package org.simplepoint.cloud.oauth.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.browser.fixture.BusinessEndpoint;
import org.simplepoint.cache.CacheService;
import org.simplepoint.cloud.oauth.server.browser.BrowserSessionController;
import org.simplepoint.cloud.oauth.server.browser.BrowserSessionSecurityConfiguration;
import org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication;
import org.simplepoint.cloud.oauth.server.service.LoginSession;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationContexts;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class BrowserSessionBoundaryHttpTest extends AuthenticationBoundaryHttpTest {
  private final AuthorizationContextService policies = mock(AuthorizationContextService.class);

  @Override
  void configureContext(AnnotationConfigWebApplicationContext application) {
    var types = new ArrayList<Class<?>>();
    ModuleCatalog.discover(getClass().getClassLoader())
        .modules()
        .forEach(module -> types.add(module.configuration()));
    types.add(BusinessModule.class);
    var plan = new ModuleCatalog(types).plan(List.of("authorization-http", "business"));
    var contexts = new ModuleAuthorizationContexts(plan, mock(CacheService.class), "/rpc");
    new ModuleAuthorizationInitializer(contexts)
        .prepare(
            new ModuleInitializationContext() {
              public String moduleId() {
                return "business";
              }

              public <T> T client(Class<T> contract) {
                return contract.cast(policies);
              }

              public byte[] resource(String path) {
                throw new AssertionError();
              }
            })
        .run();
    application.addBeanFactoryPostProcessor(
        new org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor() {
          public void postProcessBeanDefinitionRegistry(
              org.springframework.beans.factory.support.BeanDefinitionRegistry beans) {
            beans.registerBeanDefinition(
                "modulePlan",
                org.springframework.beans.factory.support.BeanDefinitionBuilder
                    .genericBeanDefinition(ModulePlan.class, () -> plan)
                    .getBeanDefinition());
            beans.registerBeanDefinition(
                "moduleAuthorizationContexts",
                org.springframework.beans.factory.support.BeanDefinitionBuilder
                    .genericBeanDefinition(ModuleAuthorizationContexts.class, () -> contexts)
                    .getBeanDefinition());
          }
        });
    application
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "browser", Map.of("simplepoint.security.browser.enabled", "true")));
    application.register(
        ModuleHttpAutoConfiguration.class,
        BrowserSessionSecurityConfiguration.class,
        BrowserSessionController.class,
        BusinessEndpoint.class,
        MethodSecurity.class,
        ModularAuthenticationBoundaryHttpTest.Openid.class);
  }

  @Test
  void completedSessionReadsProfileAndScopedBusinessDataWithoutLeakingGrants() throws Exception {
    User user = current();
    var session = session(user, Instant.now());
    final var original =
        ((org.springframework.security.core.context.SecurityContext)
                session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
            .getAuthentication();
    policy("records.read");
    mvc.perform(get("/userinfo").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sub").value("alice"))
        .andExpect(jsonPath("$.email").value("alice@example.test"))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.twoFactorSecret").doesNotExist())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/business/records").session(session).header("X-User-Id", "forged"))
        .andExpect(status().isOk())
        .andExpect(content().string("alice"));
    var stored =
        ((org.springframework.security.core.context.SecurityContext)
                session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
            .getAuthentication();
    assertThat(stored).isSameAs(original);
    assertThat(stored.getAuthorities()).extracting("authority").doesNotContain("records.read");
  }

  @Test
  void actualMaskedCsrfTokenIsRequiredAndPermissionsAreStillEnforced() throws Exception {
    var session = session(current(), Instant.now());
    policy("records.write");
    mvc.perform(post("/business/records").session(session)).andExpect(status().isForbidden());
    verifyNoInteractions(policies);
    var token =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                mvc.perform(get("/csrf").session(session))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
    mvc.perform(
            post("/business/records")
                .session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
        .andExpect(status().isOk())
        .andExpect(content().string("alice"));
    policy("records.read");
    mvc.perform(
            post("/business/records")
                .session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
        .andExpect(status().isForbidden());
  }

  @Test
  void pendingExpiredAndPreBoundarySessionsCannotReadBusinessData() throws Exception {
    User user = current();
    for (Instant instant : List.of(Instant.EPOCH, Instant.now().plusSeconds(60))) {
      var session = session(user, instant);
      mvc.perform(get("/business/records").session(session)).andExpect(status().isUnauthorized());
      assertThat(session.isInvalid()).isTrue();
    }
    var pending = new MockHttpSession();
    var security = SecurityContextHolder.createEmptyContext();
    security.setAuthentication(
        new PendingMfaAuthentication("alice", 0, Instant.now().plusSeconds(60)));
    pending.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
    mvc.perform(get("/business/records").session(pending)).andExpect(status().isUnauthorized());
    var old = session(user, Instant.now());
    old.removeAttribute(LoginSession.COMPLETED_AT);
    mvc.perform(get("/userinfo").session(old)).andExpect(status().isUnauthorized());
    verifyNoInteractions(policies);
  }

  @Test
  void revokedDisabledAndMissingAccountsInvalidateSessions() throws Exception {
    for (String state :
        List.of("version", "disabled", "locked", "expired", "credentials", "deleted", "soft-deleted")) {
      User user = current();
      var session = session(user, Instant.now());
      switch (state) {
        case "version" -> user.setAuthorizationVersion(1L);
        case "disabled" -> user.setEnabled(false);
        case "locked" -> user.setAccountNonLocked(false);
        case "expired" -> user.setAccountNonExpired(false);
        case "credentials" -> user.setCredentialsNonExpired(false);
        case "soft-deleted" -> user.setDeletedAt(java.time.Instant.now());
        default ->
            when(context.getBean(UserManagementService.class).findByIdForAuthorization("alice"))
                .thenReturn(Optional.empty());
      }
      mvc.perform(get("/userinfo").session(session)).andExpect(status().isUnauthorized());
      assertThat(session.isInvalid()).isTrue();
    }
    verifyNoInteractions(policies);
  }

  @Test
  void bearerUserInfoNeverFallsBackToBrowserSessionAndLogoutStillRequiresCsrf() throws Exception {
    var session = session(current(), Instant.now());
    when(context
            .getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class)
            .decode("invalid"))
        .thenThrow(
            new org.springframework.security.oauth2.jwt.BadJwtException("invalid test token"));
    mvc.perform(get("/userinfo").session(session).header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/simplepoint/authorize"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/login"));
    mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
    mvc.perform(
            post("/logout")
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(status().isFound());
    assertThat(session.isInvalid()).isTrue();
    mvc.perform(get("/userinfo")).andExpect(status().isUnauthorized());
  }

  private User current() {
    var user = new User();
    user.setId("alice");
    user.setEmail("alice@example.test");
    user.setPassword("hash");
    user.setTwoFactorSecret("encrypted");
    user.setAuthorizationVersion(0L);
    when(context.getBean(UserManagementService.class).findByIdForAuthorization("alice"))
        .thenReturn(Optional.of(user));
    return user;
  }

  private MockHttpSession session(User user, Instant instant) {
    var session = new MockHttpSession();
    var security = SecurityContextHolder.createEmptyContext();
    security.setAuthentication(LoginSession.authenticated(user));
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
    session.setAttribute(LoginSession.COMPLETED_AT, instant);
    return session;
  }

  private void policy(String permission) {
    var policy = new AuthorizationContext();
    policy.setUserId("alice");
    policy.setResources(List.of(permission));
    when(policies.checkedCalculate(any(), eq("alice"), any(), anyMap())).thenReturn(policy);
  }

  @PlatformModule(
      id = "business",
      packages = "org.simplepoint.browser.fixture",
      initializers = ModuleAuthorizationInitializer.class)
  @ModuleHttp(namespace = "/business")
  static class BusinessModule {}

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurity {}
}
