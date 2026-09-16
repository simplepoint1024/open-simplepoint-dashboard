package org.simplepoint.security.context;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.springframework.security.authentication.BadCredentialsException;

class AuthorizationContextCacheSecurityTest {
  final CacheService cache = mock(CacheService.class);
  final AuthorizationContextService service = mock(AuthorizationContextService.class);
  final Map<String, AuthorizationContext> stored = new HashMap<>();
  final AuthorizationContextResolver resolver = new AuthorizationContextResolver("test:", cache, service, URI.create("http://localhost/unused"));

  @BeforeEach
  void setup() {
    when(cache.get(anyString(), eq(AuthorizationContext.class))).thenAnswer(call -> stored.get(call.getArgument(0)));
    doAnswer(call -> { stored.put(call.getArgument(0), call.getArgument(1)); return null; })
        .when(cache).put(anyString(), any(AuthorizationContext.class), anyLong());
    when(service.currentVersion("t1")).thenReturn(1L);
    when(service.calculate(any(), anyString(), any(), anyMap())).thenAnswer(call -> {
      AuthorizationContext ctx = new AuthorizationContext();
      ctx.setUserId(call.getArgument(1));
      ctx.setAttributes(new HashMap<>(call.getArgument(3)));
      ctx.setVersion(service.currentVersion(call.getArgument(0)));
      return ctx;
    });
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
    assertThat(resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1", "X-Role-Id", "r2", "X-Context-Id", "same-hint")))
        .isNotSameAs(first);
    assertThat(stored).hasSize(3);
  }

  @Test
  void forgedIdentityOrganizationAndAdministratorHeadersAreNotForwarded() {
    AuthorizationContext result = resolver.resolveAuthenticated("u1", Map.of(
        "X-Tenant-Id", "t1", "X-User-Id", "admin", "X-Org-Dept-Id", "secret-dept",
        "X-Scope-Type", "PLATFORM", "X-Actor-Role", "PLATFORM_ADMIN"));
    assertThat(result.getAttributes()).containsEntry("X-User-Id", "u1")
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
    assertThat(resolver.resolveAuthenticated("u1", Map.of("X-Tenant-Id", "t1")).getUserId()).isEqualTo("u1");
    verify(service, times(2)).calculate(any(), anyString(), any(), anyMap());
  }

  @Test
  void missingSubjectNeverQueriesCacheOrPolicy() {
    clearInvocations(cache, service);
    assertThatThrownBy(() -> resolver.resolveAuthenticated(" ", Map.of())).isInstanceOf(BadCredentialsException.class);
    verifyNoInteractions(cache, service);
  }
}
