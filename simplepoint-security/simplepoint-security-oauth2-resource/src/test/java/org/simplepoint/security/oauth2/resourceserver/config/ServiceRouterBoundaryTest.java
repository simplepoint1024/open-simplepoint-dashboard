package org.simplepoint.security.oauth2.resourceserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.simplepoint.security.token.TokenRevocationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** Exercises the real filter chain, not just the positive internal-token matcher. */
class ServiceRouterBoundaryTest {
  @Test
  void ordinaryLoginCannotFallThroughSharedTokenBoundary() throws Exception {
    assertThat(invoke("shared-token", "configured-secret", null, "ROLE_USER")).isEqualTo(403);
    assertThat(invoke("shared-token", "configured-secret", "wrong", "ROLE_USER")).isEqualTo(403);
  }

  @Test
  void missingOrUnknownInternalConfigurationFailsClosedEvenForLoggedInUsers() throws Exception {
    assertThat(invoke("shared-token", "", null, "ROLE_USER")).isEqualTo(403);
    assertThat(invoke("typo", "configured-secret", "configured-secret", "ROLE_USER"))
        .isEqualTo(403);
  }

  @Test
  void validSharedTokenRemainsUsableByAnInternalService() throws Exception {
    assertThat(invoke("shared-token", "configured-secret", "configured-secret", null))
        .isEqualTo(204);
  }

  @Test
  void oauthModeRequiresServiceAuthorityAndDoesNotAcceptSharedTokenFallback() throws Exception {
    assertThat(invoke("oauth2", "configured-secret", "configured-secret", "ROLE_USER"))
        .isEqualTo(403);
    assertThat(invoke("oauth2", "", null, "SCOPE_service-router.invoke")).isEqualTo(204);
  }

  private int invoke(String mode, String token, String supplied, String authority)
      throws Exception {
    try (var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MockPropertySource()
                  .withProperty("simplepoint.service-router.internal-auth.mode", mode)
                  .withProperty("simplepoint.service-router.internal-auth.token", token));
      context.register(SecurityConfiguration.class);
      context.refresh();
      var request = new MockHttpServletRequest("POST", "/_simplepoint/service-router/invoke");
      request.setServletPath(request.getRequestURI());
      if (supplied != null) {
        request.addHeader("X-SimplePoint-Service-Router-Token", supplied);
      }
      if (authority != null) {
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "caller",
                "unused",
                Collections.singletonList(new SimpleGrantedAuthority(authority)));
        request
            .getSession()
            .setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
      }
      var response = new MockHttpServletResponse();
      context
          .getBean(FilterChainProxy.class)
          .doFilter(
              request, response, (ignoredRequest, ignoredResponse) -> response.setStatus(204));
      return response.getStatus();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder() {
      return mock(JwtDecoder.class);
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, Environment environment) throws Exception {
      return new ResourceServerAutoConfiguration()
          .securityWebFilterChain(
              http,
              claims -> Collections.emptyList(),
              mock(AuthorizationContextResolver.class),
              mock(TokenRevocationService.class),
              environment);
    }
  }
}
