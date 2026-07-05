package org.simplepoint.security.context;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.cache.CacheService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;

class AuthorizationContextResolverTest {

  @Test
  void resolve_userInfoWithoutSubjectThrowsBadCredentialsException() {
    CacheService cacheService = mock(CacheService.class);
    AuthorizationContextService contextService = mock(AuthorizationContextService.class);
    AuthorizationContextResolver resolver = new StubAuthorizationContextResolver(
        cacheService,
        contextService,
        Map.of("preferred_username", "service-client")
    );

    assertThatThrownBy(() -> resolver.resolve(Map.of(HttpHeaders.AUTHORIZATION, "Bearer token")))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessageContaining("无法解析认证主体");
    verifyNoInteractions(contextService);
  }

  private static class StubAuthorizationContextResolver extends AuthorizationContextResolver {

    private final Map<String, Object> userInfo;

    StubAuthorizationContextResolver(
        CacheService cacheService,
        AuthorizationContextService contextService,
        Map<String, Object> userInfo
    ) {
      super("test:", cacheService, contextService, URI.create("http://localhost/userinfo"));
      this.userInfo = userInfo;
    }

    @Override
    protected Map<String, Object> getUserInfo(String authorizationHeader) {
      return userInfo;
    }
  }
}
