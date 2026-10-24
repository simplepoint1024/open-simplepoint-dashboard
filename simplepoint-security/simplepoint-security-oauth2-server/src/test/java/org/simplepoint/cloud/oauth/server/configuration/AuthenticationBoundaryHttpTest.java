package org.simplepoint.cloud.oauth.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.cloud.oauth.server.client.ClientIdMetadataDocumentProperties;
import org.simplepoint.cloud.oauth.server.client.DatabaseOauth2UserService;
import org.simplepoint.cloud.oauth.server.client.DatabaseOidcUserService;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationFailureHandler;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationSuccessHandler;
import org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication;
import org.simplepoint.cloud.oauth.server.service.LoginSession;
import org.simplepoint.plugin.rbac.authentication.LoginProtection;
import org.simplepoint.plugin.rbac.authentication.MfaVerificationService;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class AuthenticationBoundaryHttpTest {
  AnnotationConfigWebApplicationContext context;
  MockMvc mvc;

  @BeforeEach
  void start() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(Config.class);
    configureContext(context);
    context.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  void configureContext(AnnotationConfigWebApplicationContext application) {}

  @AfterEach
  void close() {
    context.close();
    SecurityContextHolder.clearContext();
  }

  @Test
  void completedPasswordLoginInitializesOneTokenBeforeParallelBrowserReads() throws Exception {
    User user = new User();
    user.setId("alice");
    user.setEmail("alice@example.test");
    user.setTwoFactorEnabled(false);
    user.setPassword(context.getBean(PasswordEncoder.class).encode("correct-password"));
    when(context.getBean(UserManagementService.class).loadUserByUsername("alice@example.test"))
        .thenReturn(user);
    // Use the real CSRF filter and repository, not the testing csrf() request postprocessor.
    var anonymous = mvc.perform(get("/login")).andReturn().getRequest();
    var loginToken = (CsrfToken) anonymous.getAttribute(CsrfToken.class.getName());
    String maskedBeforeLogin = loginToken.getToken();
    var repository = new HttpSessionCsrfTokenRepository();
    String rawBeforeLogin = repository.loadToken(anonymous).getToken();
    var session = (MockHttpSession) anonymous.getSession();
    String previousId = session.getId();
    var login = mvc.perform(post("/login").session(session)
            .header("X-CSRF-TOKEN", maskedBeforeLogin)
            .param("username", "alice@example.test").param("password", "correct-password"))
        .andExpect(status().isFound()).andReturn();
    assertThat(session.getId()).isNotEqualTo(previousId);
    assertThat(session.getAttribute(LoginSession.COMPLETED_AT)).isInstanceOf(Instant.class);
    assertThat(repository.loadToken(login.getRequest())).isNotNull();
    String rawAfterLogin = repository.loadToken(login.getRequest()).getToken();
    assertThat(rawAfterLogin).isNotEqualTo(rawBeforeLogin);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = java.util.stream.IntStream.range(0, 24)
          .<java.util.concurrent.Callable<String>>mapToObj(i -> () -> {
            var read = mvc.perform(get("/account/2fa").session(session))
                .andExpect(status().isNotFound()).andReturn();
            var token = (CsrfToken) read.getRequest().getAttribute(CsrfToken.class.getName());
            String masked = token.getToken();
            assertThat(repository.loadToken(read.getRequest()).getToken()).isEqualTo(rawAfterLogin);
            assertThat(masked).isNotEqualTo(rawAfterLogin);
            return masked;
          }).toList();
      for (var result : executor.invokeAll(tasks)) {
        mvc.perform(post("/account/2fa/mutation").session(session).header("X-CSRF-TOKEN", result.get()))
            .andExpect(status().isNotFound());
      }
    }
    mvc.perform(post("/account/2fa/mutation").session(session)).andExpect(status().isForbidden());
    mvc.perform(post("/account/2fa/mutation").session(session).header("X-CSRF-TOKEN", maskedBeforeLogin))
        .andExpect(status().isForbidden());
  }

  @Test
  void pendingIdentityCannotAuthorizeChangeAccountOrLinkExternalIdentity() throws Exception {
    MockHttpSession session = pending(Instant.now().plusSeconds(60));
    for (String path :
        List.of("/oauth2/authorize", "/account/2fa", "/account/external-identities")) {
      mvc.perform(get(path).session(session))
          .andExpect(status().isFound())
          .andExpect(redirectedUrl("/two-factor/verify"));
    }
    for (String path :
        List.of("/oauth2/authorize", "/account/2fa/disable", "/external-account/link")) {
      mvc.perform(post(path).session(session).with(csrf())).andExpect(status().isForbidden());
    }
  }

  @Test
  void expiredChallengeClearsSessionAndBrowserProtocolWritesRequireCsrf() throws Exception {
    MockHttpSession session = pending(Instant.EPOCH);
    mvc.perform(get("/oauth2/authorize").session(session))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/login?error=invalid_credentials"));
    assertThat(session.isInvalid()).isTrue();
    mvc.perform(post("/login").param("username", "alice").param("password", "wrong"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/oauth2/authorize")).andExpect(status().isForbidden());
    mvc.perform(post("/oauth2/device_verification")).andExpect(status().isForbidden());
    // Machine token requests reach protocol validation rather than a browser CSRF gate.
    assertThat(mvc.perform(post("/oauth2/token")).andReturn().getResponse().getStatus())
        .isNotEqualTo(403);
  }

  @Test
  void realPasswordLoginPersistsChallengeWithoutIssuingFullAuthentication() throws Exception {
    User user = new User();
    user.setId("alice");
    user.setEmail("alice@example.test");
    user.setTwoFactorEnabled(true);
    user.setTwoFactorSecret("encrypted");
    user.setPassword(context.getBean(PasswordEncoder.class).encode("correct-password"));
    when(context.getBean(UserManagementService.class).loadUserByUsername("alice@example.test"))
        .thenReturn(user);
    var result =
        mvc.perform(
                post("/login")
                    .with(csrf())
                    .param("username", "alice@example.test")
                    .param("password", "correct-password"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/two-factor/verify"))
            .andReturn();
    var security =
        (org.springframework.security.core.context.SecurityContext)
            result
                .getRequest()
                .getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(security.getAuthentication()).isInstanceOf(PendingMfaAuthentication.class);
    assertThat(security.getAuthentication().isAuthenticated()).isFalse();
    verify(context.getBean(LoginProtection.class)).reserve("password-account", "alice");
    verifyNoInteractions(context.getBean(LoginAuditEventPublisher.class));
  }

  @Test
  void completedChallengeRotatesAndPersistsSessionWithNoSecrets() throws Exception {
    MockHttpSession session = pending(Instant.now().plusSeconds(60));
    final String previousId = session.getId();
    User user = new User();
    user.setId("alice");
    user.setPassword("hash");
    user.setTwoFactorSecret("ciphertext");
    when(context
            .getBean(MfaVerificationService.class)
            .verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("123456")))
        .thenReturn(user);
    mvc.perform(post("/two-factor/verify").session(session).with(csrf()).param("code", "123456"))
        .andExpect(status().isFound());
    var security =
        (org.springframework.security.core.context.SecurityContext)
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(session.getId()).isNotEqualTo(previousId);
    assertThat(security.getAuthentication().isAuthenticated()).isTrue();
    assertThat(((User) security.getAuthentication().getPrincipal()).getPassword()).isNull();
    assertThat(((User) security.getAuthentication().getPrincipal()).getTwoFactorSecret()).isNull();
    assertThat(session.getAttribute(LoginSession.COMPLETED_AT)).isInstanceOf(Instant.class);
    // No MVC handler is registered for this fixture path; reaching 404 proves the gate let it
    // through.
    mvc.perform(get("/account/2fa").session(session)).andExpect(status().isNotFound());
  }

  private MockHttpSession pending(Instant expiry) {
    var session = new MockHttpSession();
    var security = SecurityContextHolder.createEmptyContext();
    security.setAuthentication(new PendingMfaAuthentication("alice", 0, expiry));
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
    return session;
  }

  @Configuration
  @EnableWebMvc
  @Import({
    AuthorizationServerConfiguration.class,
    org.simplepoint.cloud.oauth.server.controller.TwoFactorController.class
  })
  static class Config {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    ClientIdMetadataDocumentProperties metadataProperties() {
      return new ClientIdMetadataDocumentProperties();
    }

    @Bean(name = "registeredClientRepositoryImpl")
    RegisteredClientRepository clients() {
      return new InMemoryRegisteredClientRepository(
          RegisteredClient.withId("client")
              .clientId("client")
              .clientSecret("{noop}test-secret")
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .build());
    }

    @Bean
    JwtDecoder jwtDecoder() {
      return mock(JwtDecoder.class);
    }

    @Bean
    UserManagementService users() {
      return mock(UserManagementService.class);
    }

    @Bean
    PasswordEncoder passwords() {
      return new BCryptPasswordEncoder(4);
    }

    @Bean
    MfaVerificationService verification() {
      return mock(MfaVerificationService.class);
    }

    @Bean
    LoginProtection protection() {
      return mock(LoginProtection.class);
    }

    @Bean
    LoginAuditEventPublisher audit() {
      return mock(LoginAuditEventPublisher.class);
    }

    @Bean
    ExternalIdentityLinkFlow links() {
      return mock(ExternalIdentityLinkFlow.class);
    }

    @Bean
    LoginAuthenticationSuccessHandler success(
        LoginAuditEventPublisher audit,
        UserManagementService users,
        ExternalIdentityLinkFlow links,
        LoginProtection protection) {
      return new LoginAuthenticationSuccessHandler(audit, users, links, protection);
    }

    @Bean
    LoginAuthenticationFailureHandler failure(
        LoginAuditEventPublisher audit, ExternalIdentityLinkFlow links) {
      return new LoginAuthenticationFailureHandler(audit, links);
    }

    @Bean
    DatabaseOauth2UserService oauthUsers() {
      return mock(DatabaseOauth2UserService.class);
    }

    @Bean
    DatabaseOidcUserService oidcUsers() {
      return mock(DatabaseOidcUserService.class);
    }

    @Bean
    ClientRegistrationRepository clientRegistrations() {
      return new InMemoryClientRegistrationRepository(
          ClientRegistration.withRegistrationId("external")
              .clientId("client")
              .clientSecret("test")
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri("http://localhost/login/oauth2/code/external")
              .authorizationUri("https://id.example/authorize")
              .tokenUri("https://id.example/token")
              .userInfoUri("https://id.example/userinfo")
              .userNameAttributeName("sub")
              .build());
    }
  }
}
