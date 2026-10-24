package org.simplepoint.security.oauth2.resourceserver.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
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
  private final ClasspathResourceScopeRegistry registry =
      new ClasspathResourceScopeRegistry(
          Map.of(
              "system.read", Set.of(ResourceScopeType.SYSTEM),
              "tenant.read", Set.of(ResourceScopeType.TENANT),
              "tenant.page.view", Set.of(ResourceScopeType.TENANT)),
          Map.of("tenant.read", "tenant.page.view"));
  private final ResourceScopeHandlerInterceptor interceptor =
      new ResourceScopeHandlerInterceptor(registry);

  @AfterEach
  void clearRequestContext() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    ResourcePolicyContext.clear();
  }

  @Test
  void extractsSingleAndMultiAuthorityExpressions() {
    assertThat(
            ResourceScopeHandlerInterceptor.extractAuthorities(
                "hasAuthority('system.read') or hasAnyAuthority(\"tenant.read\", 'personal.read')"))
        .containsExactly("system.read", "tenant.read", "personal.read");
  }

  @Test
  void rejectsTenantAuthorityFromPlatformWorkspaceEvenForAdministrator() throws Exception {
    bindContext(AuthorizationScopeType.PLATFORM, true);

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("tenantOnly")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void permitsTheScopeCompatibleAlternativeOfMixedEndpoint() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read"));

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

  @Test
  void unrelatedBusinessPoliciesDoNotBlockMultiPageWorkflowReads() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read"));
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    context.setResourceDataScopes(
        Map.of("business.expenses.view", new ResourceDataScope("SELF", Set.of(), false)));
    context.setResourceFieldPermissions(
        Map.of("business.expenses.view", Map.of("amount", "HIDDEN")));
    ResourcePolicyContext.set("business.expenses.view");

    assertThat(multiPageInterceptor().preHandle(request, response, handler("multiplePages")))
        .isTrue();
    assertThat(ResourcePolicyContext.get()).isNull();
  }

  @Test
  void ambiguousRelevantRowPoliciesRemainDenied() {
    for (String page : Set.of("tenant.page.view", "other.page.view")) {
      bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read", "other.read"));
      AuthorizationContextHolder.getContext()
          .setResourceDataScopes(Map.of(page, new ResourceDataScope("SELF", Set.of(), false)));

      assertThatThrownBy(
              () -> multiPageInterceptor().preHandle(request, response, handler("multiplePages")))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(ResourcePolicyContext.get()).isNull();
    }
  }

  @Test
  void ambiguousRelevantFieldPoliciesRemainDenied() {
    for (String page : Set.of("tenant.page.view", "other.page.view")) {
      bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read", "other.read"));
      AuthorizationContextHolder.getContext()
          .setResourceFieldPermissions(Map.of(page, Map.of("amount", "HIDDEN")));

      assertThatThrownBy(
              () -> multiPageInterceptor().preHandle(request, response, handler("multiplePages")))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(ResourcePolicyContext.get()).isNull();
    }
  }

  private ResourceScopeHandlerInterceptor multiPageInterceptor() {
    return new ResourceScopeHandlerInterceptor(
        new ClasspathResourceScopeRegistry(
            Map.of(
                "tenant.read",
                Set.of(ResourceScopeType.TENANT),
                "other.read",
                Set.of(ResourceScopeType.TENANT)),
            Map.of("tenant.read", "tenant.page.view", "other.read", "other.page.view")));
  }

  @Test
  void domainAuthorizationKeepsItsOwnPolicyBoundaryWithoutBypassingWorkspaceScope()
      throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read"));
    var context = AuthorizationContextHolder.getContext();
    context.setResourceDataScopes(
        Map.of("tenant.page.view", new ResourceDataScope("SELF", Set.of(), false)));
    context.setResourceFieldPermissions(Map.of("other.page.view", Map.of("amount", "HIDDEN")));
    assertThat(multiPageInterceptor().preHandle(request, response, handler("domainPages")))
        .isTrue();
    assertThat(ResourcePolicyContext.get()).isNull();

    bindContext(AuthorizationScopeType.PLATFORM, true, Set.of("tenant.read"));
    assertThatThrownBy(
            () -> multiPageInterceptor().preHandle(request, response, handler("domainPages")))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void domainAuthorizationCannotDisableStandardCrudOrDeclaredPagePolicies() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, false, Set.of("tenant.read"));
    for (HandlerMethod method :
        new HandlerMethod[] {
          new HandlerMethod(new InvalidDomainCrud(), "schema"),
          new HandlerMethod(new InvalidDomainPage(), "list")
        }) {
      assertThatThrownBy(() -> interceptor.preHandle(request, response, method))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(ResourcePolicyContext.get()).isNull();
    }
  }

  @org.simplepoint.core.permission.DomainDataAuthorization
  private static class InvalidDomainCrud extends AuthenticatedOrders {}

  @org.simplepoint.core.permission.DomainDataAuthorization
  private static class InvalidDomainPage extends DeclaredPage {}

  @org.simplepoint.core.permission.PermissionPage(value = "tenant.page.view", model = String.class)
  private static class DeclaredPage {
    public void list() {}
  }

  @PreAuthorize("isAuthenticated()")
  private static class AuthenticatedOrders
      extends org.simplepoint.core.base.controller.BaseController<
          org.simplepoint.api.base.BaseService<org.simplepoint.security.entity.Role, String>,
          org.simplepoint.security.entity.Role,
          String> {
    AuthenticatedOrders() {
      super(org.mockito.Mockito.mock(org.simplepoint.api.base.BaseService.class));
    }

    @org.springframework.web.bind.annotation.GetMapping
    @PreAuthorize("hasAuthority('tenant.read')")
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
    assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
        .isInstanceOf(AccessDeniedException.class);
    bindContext(AuthorizationScopeType.PLATFORM, true, Set.of("tenant.read"));
    assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
        .isInstanceOf(AccessDeniedException.class);
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

  @Test
  void administratorInTenantWorkspaceCannotBypassMissingEntitlement() throws Exception {
    bindContext(AuthorizationScopeType.TENANT, true);
    assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("tenantOnly")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    request, response, new HandlerMethod(new AuthenticatedOrders(), "schema")))
        .isInstanceOf(AccessDeniedException.class);
    bindContext(AuthorizationScopeType.TENANT, true, Set.of("tenant.read"));
    assertThat(interceptor.preHandle(request, response, handler("tenantOnly"))).isTrue();
  }

  @Test
  void personalAdministratorAlsoRequiresItsValidatedDirectoryPermission() throws Exception {
    var personal =
        new ResourceScopeHandlerInterceptor(
            new ClasspathResourceScopeRegistry(
                Map.of("tenant.read", Set.of(ResourceScopeType.PERSONAL)), Map.of()));
    bindContext(AuthorizationScopeType.PERSONAL, true);
    assertThatThrownBy(() -> personal.preHandle(request, response, handler("tenantOnly")))
        .isInstanceOf(AccessDeniedException.class);
    bindContext(AuthorizationScopeType.PERSONAL, true, Set.of("tenant.read"));
    assertThat(personal.preHandle(request, response, handler("tenantOnly"))).isTrue();
  }

  private void bindContext(AuthorizationScopeType scopeType, boolean administrator) {
    bindContext(scopeType, administrator, Set.of());
  }

  private void bindContext(
      AuthorizationScopeType scopeType, boolean administrator, Set<String> resources) {
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(request));
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
    public void tenantOnly() {}

    @PreAuthorize("hasAnyAuthority('system.read', 'tenant.read')")
    public void mixed() {}

    @PreAuthorize("hasAnyAuthority('tenant.read', 'other.read')")
    public void multiplePages() {}

    @org.simplepoint.core.permission.DomainDataAuthorization
    @PreAuthorize("hasAnyAuthority('tenant.read', 'other.read')")
    public void domainPages() {}

    @PreAuthorize("hasAuthority('legacy.unknown')")
    public void unknown() {}
  }
}
