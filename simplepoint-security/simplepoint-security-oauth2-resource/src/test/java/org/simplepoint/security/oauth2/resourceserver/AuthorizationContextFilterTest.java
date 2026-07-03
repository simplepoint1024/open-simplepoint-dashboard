package org.simplepoint.security.oauth2.resourceserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthorizationContextFilterTest {

  @AfterEach
  void tearDown() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void doFilterInternal_resolvesContextWhenOnlyAuthorizationHeaderPresent() throws ServletException, IOException {
    AuthorizationContextResolver resolver = mock(AuthorizationContextResolver.class);
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("u1");
    when(resolver.load(null)).thenReturn(null);
    when(resolver.resolve(org.mockito.ArgumentMatchers.anyMap())).thenReturn(context);

    final AuthorizationContextFilter filter = new AuthorizationContextFilter(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menus/service-routes");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

    final AuthorizationContext[] seen = new AuthorizationContext[1];
    FilterChain chain = (req, res) -> seen[0] =
        RequestContextHolder.getContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, AuthorizationContext.class);

    filter.doFilter(request, response, chain);

    assertThat(seen[0]).isSameAs(context);
    assertThat(RequestContextHolder.getContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, AuthorizationContext.class))
        .isNull();
  }

  @Test
  void doFilterInternal_ignoresContextHeadersWithoutAuthorization() throws ServletException, IOException {
    AuthorizationContextResolver resolver = mock(AuthorizationContextResolver.class);
    final AuthorizationContextFilter filter = new AuthorizationContextFilter(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menus/service-routes");
    request.addHeader("X-Context-Id", "ctx1");
    request.addHeader("X-Tenant-Id", "tenant-a");
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

    final boolean[] chainCalled = new boolean[1];
    FilterChain chain = (req, res) -> chainCalled[0] = true;

    filter.doFilter(request, response, chain);

    assertThat(chainCalled[0]).isTrue();
    verifyNoInteractions(resolver);
    assertThat(RequestContextHolder.getContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, AuthorizationContext.class))
        .isNull();
  }

  @Test
  void doFilterInternal_rejectsCachedContextWhenTenantDoesNotMatch() throws ServletException, IOException {
    AuthorizationContextResolver resolver = mock(AuthorizationContextResolver.class);
    AuthorizationContext cached = new AuthorizationContext();
    cached.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    when(resolver.load("ctx1")).thenReturn(cached);

    final AuthorizationContextFilter filter = new AuthorizationContextFilter(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menus/service-routes");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
    request.addHeader("X-Context-Id", "ctx1");
    request.addHeader("X-Tenant-Id", "tenant-b");
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

    final boolean[] chainCalled = new boolean[1];
    FilterChain chain = (req, res) -> chainCalled[0] = true;

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(MockHttpServletResponse.SC_FORBIDDEN);
    assertThat(chainCalled[0]).isFalse();
    assertThat(RequestContextHolder.getContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, AuthorizationContext.class))
        .isNull();
  }

  @Test
  void doFilterInternal_rejectsCachedContextWhenRoleDoesNotMatch() throws ServletException, IOException {
    AuthorizationContextResolver resolver = mock(AuthorizationContextResolver.class);
    AuthorizationContext cached = new AuthorizationContext();
    cached.setAttributes(Map.of("X-Tenant-Id", "tenant-a", "X-Role-Id", "role-a"));
    when(resolver.load("ctx1")).thenReturn(cached);

    final AuthorizationContextFilter filter = new AuthorizationContextFilter(resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menus/service-routes");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
    request.addHeader("X-Context-Id", "ctx1");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader("X-Role-Id", "role-b");
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

    final boolean[] chainCalled = new boolean[1];
    FilterChain chain = (req, res) -> chainCalled[0] = true;

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(MockHttpServletResponse.SC_FORBIDDEN);
    assertThat(chainCalled[0]).isFalse();
  }

  @Test
  void doFilterInternal_keepsProtectedHeadersFromCachedContext() throws ServletException, IOException {
    AuthorizationContextResolver resolver = mock(AuthorizationContextResolver.class);
    AuthorizationContext cached = new AuthorizationContext();
    cached.setAttributes(Map.of(
        "X-Tenant-Id", "tenant-a",
        "X-Role-Id", "role-a",
        "X-User-Id", "user-a",
        "X-Scope-Type", "TENANT",
        "X-Actor-Role", "TENANT_ADMIN"
    ));
    when(resolver.load("ctx1")).thenReturn(cached);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menus/service-routes");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
    request.addHeader("X-Context-Id", "ctx1");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader("X-Role-Id", "role-a");
    request.addHeader("X-User-Id", "user-b");
    request.addHeader("X-Scope-Type", "PLATFORM");
    request.addHeader("X-Actor-Role", "PLATFORM_ADMIN");
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

    final AuthorizationContext[] seen = new AuthorizationContext[1];
    FilterChain chain = (req, res) -> seen[0] =
        RequestContextHolder.getContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, AuthorizationContext.class);

    AuthorizationContextFilter filter = new AuthorizationContextFilter(resolver);
    filter.doFilter(request, response, chain);

    assertThat(seen[0]).isSameAs(cached);
    assertThat(cached.getAttribute("X-Tenant-Id")).isEqualTo("tenant-a");
    assertThat(cached.getAttribute("X-Role-Id")).isEqualTo("role-a");
    assertThat(cached.getAttribute("X-User-Id")).isEqualTo("user-a");
    assertThat(cached.getAttribute("X-Scope-Type")).isEqualTo("TENANT");
    assertThat(cached.getAttribute("X-Actor-Role")).isEqualTo("TENANT_ADMIN");
    assertThat(cached.getAttribute("X-Context-Id")).isEqualTo("ctx1");
  }
}
