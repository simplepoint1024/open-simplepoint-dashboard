package org.simplepoint.security.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.cache.CacheService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class AuthorizationContextRejectionTest {
  @Test
  void checkedContractRetainsTypedRejectionsAtEveryRemoteBoundary() {
    var service = mock(AuthorizationContextService.class, CALLS_REAL_METHODS);
    when(service.currentVersion("t")).thenThrow(new AccessDeniedException("private tenant"));
    when(service.currentSubjectVersion("u"))
        .thenThrow(new BadCredentialsException("private account"));
    when(service.calculate(eq("t"), eq("u"), any(), anyMap()))
        .thenThrow(new AccessDeniedException("private role"));
    assertThatThrownBy(() -> service.checkedCurrentVersion("t"))
        .isInstanceOf(AuthorizationContextRejection.class)
        .hasMessage("Authorization context access denied");
    assertThatThrownBy(() -> service.checkedCurrentSubjectVersion("u"))
        .isInstanceOf(AuthorizationContextRejection.class)
        .hasMessage("Authentication is required");
    assertThatThrownBy(() -> service.checkedCalculate("t", "u", null, Map.of()))
        .isInstanceOf(AuthorizationContextRejection.class)
        .hasMessage("Authorization context access denied");
    // Existing local callers retain their Spring Security contract.
    assertThatThrownBy(() -> service.calculate("t", "u", null, Map.of()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void decoderUsesExactCodesAndIgnoresUntrustedRemoteMessages() {
    var decoder = new AuthorizationContextErrorDecoder();
    for (var reason : AuthorizationContextRejection.Reason.values()) {
      var source = new AuthorizationContextRejection(reason);
      var result = decoder.decode(source.remoteErrorCode(), "submitted-secret-value");
      assertThat(result.reason()).isEqualTo(reason);
      assertThat(result.getMessage()).doesNotContain("submitted-secret-value");
    }
    assertThat(decoder.decode(null, "access denied")).isNull();
    assertThat(decoder.decode("REMOTE_SERVICE_ERROR", "access denied")).isNull();
    assertThat(decoder.decode("AUTHORIZATION_CONTEXT:UNKNOWN", "access denied")).isNull();
    assertThat(decoder.decode("AUTHORIZATION_CONTEXT:ACCESS_DENIED:suffix", "access denied"))
        .isNull();
  }

  @Test
  void resolverMapsDecodedRejectionsWithoutCachingOrResolvingBusinessContext() {
    for (var reason : AuthorizationContextRejection.Reason.values()) {
      var service = mock(AuthorizationContextService.class);
      var cache = mock(CacheService.class);
      when(service.checkedCurrentVersion("t")).thenThrow(new AuthorizationContextRejection(reason));
      var resolver =
          new AuthorizationContextResolver(
              "test:", cache, service, URI.create("http://localhost/unused"));
      assertThatThrownBy(() -> resolver.resolveAuthenticated("u", Map.of("X-Tenant-Id", "t")))
          .isInstanceOf(
              reason == AuthorizationContextRejection.Reason.ACCESS_DENIED
                  ? AccessDeniedException.class
                  : BadCredentialsException.class);
      verifyNoInteractions(cache);
      verify(service, never()).checkedCalculate(any(), any(), any(), any());
    }
  }

  @Test
  void infrastructureFailureRetainsIdentityAndNeverBecomesPermissionDenial() {
    var service = mock(AuthorizationContextService.class, CALLS_REAL_METHODS);
    var unavailable = new IllegalStateException("database unavailable");
    when(service.currentVersion("t")).thenThrow(unavailable);
    assertThatThrownBy(() -> service.checkedCurrentVersion("t")).isSameAs(unavailable);
    var resolver =
        new AuthorizationContextResolver(
            "test:", mock(CacheService.class), service, URI.create("http://localhost/unused"));
    assertThatThrownBy(() -> resolver.resolveAuthenticated("u", Map.of("X-Tenant-Id", "t")))
        .isSameAs(unavailable);
  }
}
