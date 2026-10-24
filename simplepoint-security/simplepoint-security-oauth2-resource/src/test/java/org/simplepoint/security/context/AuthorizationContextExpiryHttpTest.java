package org.simplepoint.security.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.security.oauth2.resourceserver.AuthorizationContextFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Real resolver/context/security filters with deterministic time and a deliberately non-expiring
 * cache.
 */
class AuthorizationContextExpiryHttpTest {
  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
    org.simplepoint.core.RequestContextHolder.clearContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sameBearerOrBrowserSessionLosesPaidApiAccessAtTheExactDeadline(boolean browserSession)
      throws Exception {
    final Instant start = Instant.parse("2026-10-23T06:00:00Z");
    final Instant deadline = start.plusSeconds(30);
    final var time = new AtomicReference<>(start);
    final Clock clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(call -> time.get());
    final var cache = mock(CacheService.class);
    final var stored = new HashMap<String, AuthorizationContext>();
    when(cache.get(anyString(), eq(AuthorizationContext.class)))
        .thenAnswer(call -> stored.get(call.getArgument(0)));
    doAnswer(
            call -> {
              stored.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(cache)
        .put(anyString(), any(AuthorizationContext.class), anyLong());
    final var service = mock(AuthorizationContextService.class, CALLS_REAL_METHODS);
    when(service.currentVersion("tenant")).thenReturn(1L);
    when(service.currentSubjectVersion("subject")).thenReturn(1L);
    when(service.calculate(any(), anyString(), any(), anyMap()))
        .thenAnswer(
            call -> {
              final var context = new AuthorizationContext();
              context.setUserId("subject");
              context.setScopeType(AuthorizationScopeType.TENANT);
              context.setAttributes(Map.of("X-Tenant-Id", "tenant"));
              context.setVersion(1L);
              boolean active = time.get().isBefore(deadline);
              context.setResources(active ? Set.of("paid.page") : Set.of());
              context.setValidUntil(active ? deadline : null);
              return context;
            });
    final var resolver =
        new AuthorizationContextResolver("http-expiry:", cache, service, null, clock);
    final var filter =
        browserSession
            ? new AuthorizationContextFilter(
                () -> resolver,
                "/_simplepoint/service-router/invoke",
                "",
                authentication -> authentication.getName())
            : new AuthorizationContextFilter(resolver);
    final var authorization =
        new AuthorizationFilter(AuthorityAuthorizationManager.hasAuthority("paid.page"));
    final var authentication =
        browserSession
            ? UsernamePasswordAuthenticationToken.authenticated(
                "subject", null, List.of(new SimpleGrantedAuthority("paid.page")))
            : new JwtAuthenticationToken(
                Jwt.withTokenValue("same-token").header("alg", "RS256").subject("subject").build(),
                List.of(new SimpleGrantedAuthority("SCOPE_read")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    final var effects = new AtomicInteger();
    for (int attempt = 0; attempt < 3; attempt++) {
      time.set(attempt == 0 ? start : attempt == 1 ? deadline.minusNanos(1) : deadline);
      final var request = new MockHttpServletRequest("POST", "/business/paid-records");
      if (!browserSession) {
        request.addHeader("Authorization", "Bearer same-token");
      }
      request.addHeader("X-Tenant-Id", "tenant");
      final var response = new MockHttpServletResponse();
      filter.doFilter(
          request,
          response,
          (req, res) ->
              authorization.doFilter(
                  req, res, (acceptedRequest, acceptedResponse) -> effects.incrementAndGet()));
      assertThat(response.getStatus()).isEqualTo(attempt < 2 ? 200 : 403);
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
      assertThat(AuthorizationContextHolder.getContext()).isNull();
    }
    assertThat(effects).hasValue(2);
    verify(service, times(2)).calculate(any(), eq("subject"), any(), anyMap());
  }
}
