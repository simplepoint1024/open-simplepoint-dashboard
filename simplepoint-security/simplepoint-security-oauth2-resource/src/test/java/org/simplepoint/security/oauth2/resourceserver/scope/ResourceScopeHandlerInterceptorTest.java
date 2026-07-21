package org.simplepoint.security.oauth2.resourceserver.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.security.entity.ResourceScopeType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

class ResourceScopeHandlerInterceptorTest {

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final ClasspathResourceScopeRegistry registry = new ClasspathResourceScopeRegistry(Map.of(
      "system.read", Set.of(ResourceScopeType.SYSTEM),
      "tenant.read", Set.of(ResourceScopeType.TENANT)
  ));
  private final ResourceScopeHandlerInterceptor interceptor =
      new ResourceScopeHandlerInterceptor(registry);

  @AfterEach
  void clearRequestContext() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void extractsSingleAndMultiAuthorityExpressions() {
    assertThat(ResourceScopeHandlerInterceptor.extractAuthorities(
        "hasAuthority('system.read') or hasAnyAuthority(\"tenant.read\", 'personal.read')"
    )).containsExactly("system.read", "tenant.read", "personal.read");
  }

  @Test
  void rejectsTenantAuthorityFromPlatformWorkspaceEvenForAdministrator() throws Exception {
    bindContext(AuthorizationScopeType.PLATFORM, true);

    assertThatThrownBy(() -> interceptor.preHandle(
        request,
        response,
        handler("tenantOnly")
    )).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void permitsTheScopeCompatibleAlternativeOfMixedEndpoint() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false);

    assertThat(interceptor.preHandle(request, response, handler("mixed"))).isTrue();
  }

  @Test
  void permitsDynamicAuthorityAlreadyValidatedIntoTheContext() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false, Set.of("legacy.unknown"));

    assertThat(interceptor.preHandle(request, response, handler("unknown"))).isTrue();
  }

  @Test
  void rejectsUnknownAuthorityMissingFromTheValidatedContext() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false);

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("unknown")))
        .isInstanceOf(AccessDeniedException.class);
  }

  private void bindContext(AuthorizationScopeType scopeType, boolean administrator) {
    bindContext(scopeType, administrator, Set.of());
  }

  private void bindContext(
      AuthorizationScopeType scopeType,
      boolean administrator,
      Set<String> resources
  ) {
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(request)
    );
    AuthorizationContext context = new AuthorizationContext();
    context.setScopeType(scopeType);
    context.setIsAdministrator(administrator);
    context.setResources(resources);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  private HandlerMethod handler(String name) throws NoSuchMethodException {
    Method method = TestController.class.getDeclaredMethod(name);
    return new HandlerMethod(new TestController(), method);
  }

  private static final class TestController {

    @PreAuthorize("hasRole('Administrator') or hasAuthority('tenant.read')")
    public void tenantOnly() {
    }

    @PreAuthorize("hasAnyAuthority('system.read', 'tenant.read')")
    public void mixed() {
    }

    @PreAuthorize("hasAuthority('legacy.unknown')")
    public void unknown() {
    }
  }
}
