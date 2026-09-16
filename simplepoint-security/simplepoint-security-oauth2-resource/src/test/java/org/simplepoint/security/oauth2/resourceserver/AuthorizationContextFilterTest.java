package org.simplepoint.security.oauth2.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuthorizationContextFilterTest {
  private final AuthorizationContextResolver resolver = mock(AuthorizationContextResolver.class);
  private final AuthorizationContextFilter filter = new AuthorizationContextFilter(resolver);

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    org.simplepoint.core.RequestContextHolder.clearContext(org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  private MockHttpServletRequest request(String path) {
    var request = new MockHttpServletRequest("GET", path);
    request.addHeader("Authorization", "Bearer validated-token");
    return request;
  }

  private void authenticate() {
    Jwt jwt = Jwt.withTokenValue("validated-token").header("alg", "RS256").subject("u1").build();
    SecurityContextHolder.getContext().setAuthentication(
        new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_read"))));
  }

  @Test
  void requiresValidatedJwtBeforeResolvingAuthorization() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(request("/api/data"), response, (req, res) -> { throw new AssertionError("must not run"); });
    assertThat(response.getStatus()).isEqualTo(401);
    verifyNoInteractions(resolver);
  }

  @Test
  void usesVerifiedSubjectAndInstallsContextAndAuthoritiesThenCleansUp() throws Exception {
    authenticate();
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("u1");
    context.setResources(List.of("data.view"));
    when(resolver.resolveAuthenticated(eq("u1"), anyMap())).thenReturn(context);
    var request = request("/api/data");
    request.addHeader("X-User-Id", "forged-user");
    request.addHeader("X-Context-Id", "another-users-context");
    var called = new AtomicBoolean();
    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
      called.set(true);
      assertThat(AuthorizationContextHolder.getContext()).isSameAs(context);
      assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
          .extracting("authority").contains("SCOPE_read", "data.view");
    });
    assertThat(called).isTrue();
    assertThat(AuthorizationContextHolder.getContext()).isNull();
  }

  @Test
  void resolutionFailuresNeverReachApplication() throws Exception {
    authenticate();
    for (RuntimeException error : List.of(new AccessDeniedException("forbidden"), new BadCredentialsException("invalid"))) {
      when(resolver.resolveAuthenticated(eq("u1"), anyMap())).thenThrow(error);
      var response = new MockHttpServletResponse();
      filter.doFilter(request("/api/data"), response, (req, res) -> { throw new AssertionError("must not run"); });
      assertThat(response.getStatus()).isEqualTo(error instanceof AccessDeniedException ? 403 : 401);
      assertThat(AuthorizationContextHolder.getContext()).isNull();
      reset(resolver);
    }
  }

  @Test
  void nullPolicyDoesNotAllowRequest() throws Exception {
    authenticate();
    var response = new MockHttpServletResponse();
    filter.doFilter(request("/api/data"), response, (req, res) -> { throw new AssertionError("must not run"); });
    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void noBearerHeaderDoesNotResolveContext() throws Exception {
    filter.doFilter(new MockHttpServletRequest("GET", "/public"), new MockHttpServletResponse(), (req, res) -> {});
    verifyNoInteractions(resolver);
  }

  @Test
  void internalAndStaticRoutesDoNotResolveUserPolicies() throws Exception {
    for (String path : List.of("/_simplepoint/service-router/invoke", "/static/a.js", "/mf/manifest.json", "/actuator/health", "/error")) {
      var called = new AtomicBoolean();
      filter.doFilter(request(path), new MockHttpServletResponse(), (req, res) -> called.set(true));
      assertThat(called).isTrue();
    }
    verifyNoInteractions(resolver);
  }
}
