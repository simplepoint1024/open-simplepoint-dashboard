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
import org.simplepoint.core.ResourcePolicyContext;
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
      "tenant.read", Set.of(ResourceScopeType.TENANT),
      "tenant.page.view", Set.of(ResourceScopeType.TENANT)
  ), Map.of("tenant.read", "tenant.page.view"));
  private final ResourceScopeHandlerInterceptor interceptor =
      new ResourceScopeHandlerInterceptor(registry);

  @AfterEach
  void clearRequestContext() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    ResourcePolicyContext.clear();
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
    assertThat(ResourcePolicyContext.get()).isEqualTo("tenant.page.view");
    interceptor.afterCompletion(request, response, handler("mixed"), null);
    assertThat(ResourcePolicyContext.get()).isNull();
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

  @org.simplepoint.core.permission.PermissionPage(value = "tenant.page.view", model = String.class)
  private static class DeclaredPage {
    public void list() {}
  }

  @PreAuthorize("isAuthenticated()")
  private static class AuthenticatedOrders extends org.simplepoint.core.base.controller.BaseController<
      org.simplepoint.api.base.BaseService<org.simplepoint.security.entity.Role, String>, org.simplepoint.security.entity.Role, String> {
    AuthenticatedOrders() { super(org.mockito.Mockito.mock(org.simplepoint.api.base.BaseService.class)); }
    @org.springframework.web.bind.annotation.GetMapping @PreAuthorize("hasAuthority('tenant.read')")
    public void list() {}
  }

  @Test
  void inheritedMetadataSelectsItsPageEvenWithAuthenticationOnlyOnTheClass() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read"));
    var handler = new HandlerMethod(new AuthenticatedOrders(), "schema");
    assertThat(interceptor.preHandle(request, response, handler)).isTrue();
    assertThat(ResourcePolicyContext.get()).isEqualTo("tenant.page.view");
  }

  @Test
  void inheritedMetadataRejectsMissingPageGrantsAndWrongWorkspace() throws Exception {
    var handler = new HandlerMethod(new AuthenticatedOrders(), "schema");
    bindContext(AuthorizationScopeType.TENANT, false);
    assertThatThrownBy(() -> interceptor.preHandle(request, response, handler)).isInstanceOf(AccessDeniedException.class);
    bindContext(AuthorizationScopeType.PLATFORM, true, Set.of("tenant.read"));
    assertThatThrownBy(() -> interceptor.preHandle(request, response, handler)).isInstanceOf(AccessDeniedException.class);
    assertThat(ResourcePolicyContext.get()).isNull();
  }

  @Test
  void explicitPageMetadataWorksWithoutPreAuthorizeAndCleansTheWorkerContext() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read"));
    var handler = new HandlerMethod(new DeclaredPage(), "list");
    assertThat(interceptor.preHandle(request, response, handler)).isTrue();
    assertThat(ResourcePolicyContext.get()).isEqualTo("tenant.page.view");
    interceptor.afterConcurrentHandlingStarted(request, response, handler);
    assertThat(ResourcePolicyContext.get()).isNull();
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
