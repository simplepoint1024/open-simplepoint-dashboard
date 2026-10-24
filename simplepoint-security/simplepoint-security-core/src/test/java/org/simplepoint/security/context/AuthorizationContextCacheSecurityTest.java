package org.simplepoint.security.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.springframework.security.authentication.BadCredentialsException;

class AuthorizationContextCacheSecurityTest {
  private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");
  final Clock clock = mock(Clock.class);
  final CacheService cache = mock(CacheService.class);
  final AuthorizationContextService service =
      mock(AuthorizationContextService.class, CALLS_REAL_METHODS);
  final Map<String, AuthorizationContext> stored = new HashMap<>();
  final AuthorizationContextResolver resolver =
      new AuthorizationContextResolver(
          "test:", cache, service, URI.create("http://localhost/unused"), clock);

  @BeforeEach
  void setup() {
    org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(NOW);
    when(cache.get(anyString(), eq(AuthorizationContext.class)))
        .thenAnswer(call -> stored.get(call.getArgument(0)));
    doAnswer(
            call -> {
              stored.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(cache)
        .put(anyString(), any(AuthorizationContext.class), anyLong());
    when(service.currentVersion("t1")).thenReturn(1L);
    when(service.currentSubjectVersion(anyString())).thenReturn(1L);
    when(service.calculate(any(), anyString(), any(), anyMap()))
        .thenAnswer(
            call -> {
              AuthorizationContext ctx = new AuthorizationContext();
              ctx.setUserId(call.getArgument(1));
              ctx.setAttributes(new HashMap<>(call.getArgument(3)));
              ctx.setVersion(service.currentVersion(call.getArgument(0)));
              return ctx;
            });
  }

  @Test
  void entitlementDeadlineRecalculatesEvenWhenCacheAndAuthorizationVersionsHaveNotChanged() {
    var before = contextWithDeadline(NOW.plusSeconds(30), "paid.page");
    var after = contextWithDeadline(null, "baseline.page");
    org.mockito.Mockito.doReturn(before, after)
        .when(service)
        .calculate(any(), anyString(), any(), anyMap());
    var headers = Map.of("X-Tenant-Id", "t1");
    assertThat(resolver.resolveAuthenticated("u1", headers)).isSameAs(before);
    verify(cache).put(anyString(), eq(before), eq(30L));
    when(clock.instant()).thenReturn(NOW.plusSeconds(30).minusNanos(1));
    assertThat(resolver.resolveAuthenticated("u1", headers)).isSameAs(before);
    when(clock.instant()).thenReturn(NOW.plusSeconds(30));
    assertThat(resolver.resolveAuthenticated("u1", headers).getResources())
        .containsExactly("baseline.page");
    verify(service, times(2)).calculate(any(), eq("u1"), any(), anyMap());
  }

  @Test
  void subsecondPolicyIsUsableButNeverCachedWithZeroOrRoundedUpTtl() {
    var policy = contextWithDeadline(NOW.plusNanos(123_000), "paid.page");
    org.mockito.Mockito.doReturn(policy)
        .when(service)
        .calculate(any(), anyString(), any(), anyMap());
    assertThat(resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1"))).isSameAs(policy);
    assertThat(stored).isEmpty();
  }

  @Test
  void expirationDuringCalculationIsReevaluatedBeforeReturning() {
    var expired = contextWithDeadline(NOW, "paid.page");
    var current = contextWithDeadline(null, "baseline.page");
    org.mockito.Mockito.doReturn(expired, current)
        .when(service)
        .calculate(any(), anyString(), any(), anyMap());
    assertThat(resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1"))).isSameAs(current);
    verify(service, times(2)).calculate(any(), eq("u1"), any(), anyMap());
    assertThat(stored.values()).containsExactly(current);
  }

  @Test
  void persistentlyExpiredProviderResultFailsClosedWithBoundedRecalculation() {
    org.mockito.Mockito.doReturn(contextWithDeadline(NOW, "paid.page"))
        .when(service)
        .calculate(any(), anyString(), any(), anyMap());
    assertThatThrownBy(() -> resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1")))
        .isInstanceOf(
            org.springframework.security.authentication.AuthenticationServiceException.class);
    verify(service, times(2)).calculate(any(), eq("u1"), any(), anyMap());
    assertThat(stored).isEmpty();
  }

  @Test
  void futureDeadlineDoesNotExtendTheNormalCacheLifetime() {
    var policy = contextWithDeadline(NOW.plusSeconds(86400), "paid.page");
    org.mockito.Mockito.doReturn(policy)
        .when(service)
        .calculate(any(), anyString(), any(), anyMap());
    resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1"));
    verify(cache).put(anyString(), eq(policy), eq(7200L));
    assertThat(stored.keySet()).allMatch(key -> key.contains("verified-v8:"));
  }

  private AuthorizationContext contextWithDeadline(Instant deadline, String code) {
    var context = new AuthorizationContext();
    context.setUserId("u1");
    context.setVersion(1L);
    context.setAttributes(Map.of("X-Tenant-Id", "t1"));
    context.setResources(java.util.Set.of(code));
    context.setValidUntil(deadline);
    return context;
  }

  @Test
  void sameUserTenantRoleVersionReusesButVersionChangeRecalculates() {
    var headers = Map.of("X-Tenant-Id", "t1", "X-Role-Id", "r1", "X-Context-Id", "old-hint");
    AuthorizationContext first = resolver.resolveAuthenticated("u1", headers);
    assertThat(resolver.resolveAuthenticated("u1", headers)).isSameAs(first);
    when(service.currentVersion("t1")).thenReturn(2L);
    AuthorizationContext refreshed = resolver.resolveAuthenticated("u1", headers);
    assertThat(refreshed).isNotSameAs(first);
    assertThat(refreshed.getVersion()).isEqualTo(2);
    verify(service, times(2)).calculate(any(), eq("u1"), any(), anyMap());
  }

  @Test
  void globalIdentityRevisionInvalidatesPreviouslyVisitedTenantCaches() {
    var headers = Map.of("X-Tenant-Id", "t1");
    when(service.currentSubjectVersion("u1")).thenReturn(4L);
    var first = resolver.resolveAuthenticated("u1", headers);
    assertThat(resolver.resolveAuthenticated("u1", headers)).isSameAs(first);
    when(service.currentSubjectVersion("u1")).thenReturn(5L);
    assertThat(resolver.resolveAuthenticated("u1", headers)).isNotSameAs(first);
    verify(service, times(2)).calculate(any(), eq("u1"), any(), anyMap());
  }

  @Test
  void disabledAccountCannotReuseCachedContext() {
    resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1"));
    when(service.currentSubjectVersion("u1")).thenThrow(new BadCredentialsException("disabled"));
    assertThatThrownBy(() -> resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1")))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void clientContextIdCannotReuseAnotherSubjectOrRole() {
    var headers = Map.of("X-Tenant-Id", "t1", "X-Role-Id", "r1", "X-Context-Id", "same-hint");
    AuthorizationContext first = resolver.resolveAuthenticated("u1", headers);
    assertThat(resolver.resolveAuthenticated("u2", headers).getUserId()).isEqualTo("u2");
    assertThat(
            resolver.resolveAuthenticated(
                "u1", Map.of("X-Tenant-Id", "t1", "X-Role-Id", "r2", "X-Context-Id", "same-hint")))
        .isNotSameAs(first);
    assertThat(stored).hasSize(3);
  }

  @Test
  void forgedIdentityOrganizationAndAdministratorHeadersAreNotForwarded() {
    AuthorizationContext result =
        resolver.resolveAuthenticated(
            "u1",
            Map.of(
                "X-Tenant-Id",
                "t1",
                "X-User-Id",
                "admin",
                "X-Org-Dept-Id",
                "secret-dept",
                "X-Scope-Type",
                "PLATFORM",
                "X-Actor-Role",
                "PLATFORM_ADMIN"));
    assertThat(result.getAttributes())
        .containsEntry("X-User-Id", "u1")
        .doesNotContainKeys("X-Org-Dept-Id", "X-Scope-Type", "X-Actor-Role");
  }

  @Test
  void unversionedScopeAlwaysRecalculates() {
    when(service.currentVersion("t1")).thenReturn(null);
    resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1"));
    resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1"));
    verify(service, times(2)).calculate(any(), anyString(), any(), anyMap());
    assertThat(stored).isEmpty();
  }

  @Test
  void cachedSubjectMismatchTriggersRecalculation() {
    resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1"));
    AuthorizationContext forged = new AuthorizationContext();
    forged.setUserId("u2");
    forged.setVersion(1L);
    forged.setAttributes(Map.of("X-Tenant-Id", "t1"));
    stored.replaceAll((key, value) -> forged);
    assertThat(resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1")).getUserId())
        .isEqualTo("u1");
    verify(service, times(2)).calculate(any(), anyString(), any(), anyMap());
  }

  @Test
  void missingSubjectNeverQueriesCacheOrPolicy() {
    clearInvocations(cache, service);
    assertThatThrownBy(() -> resolver.resolveAuthenticated(" ", Map.of()))
        .isInstanceOf(BadCredentialsException.class);
    verifyNoInteractions(cache, service);
  }
}
