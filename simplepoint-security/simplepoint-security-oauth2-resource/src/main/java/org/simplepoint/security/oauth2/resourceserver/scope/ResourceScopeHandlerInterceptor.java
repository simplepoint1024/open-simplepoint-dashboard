package org.simplepoint.security.oauth2.resourceserver.scope;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.security.ResourceScopePolicy;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/** Enforces the resource boundary declared by controller authority expressions. */
public class ResourceScopeHandlerInterceptor implements AsyncHandlerInterceptor {

  private static final Pattern AUTHORITY_FUNCTION = Pattern.compile(
      "has(?:Any)?Authority\\s*\\(([^)]*)\\)"
  );
  private static final Pattern QUOTED_VALUE = Pattern.compile("['\"]([^'\"]+)['\"]");

  private final ClasspathResourceScopeRegistry registry;

  /** Creates an interceptor backed by the local resource-scope registry. */
  public ResourceScopeHandlerInterceptor(final ClasspathResourceScopeRegistry registry) {
    this.registry = registry;
  }

  @Override
  public boolean preHandle(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Object handler
  ) {
    ResourcePolicyContext.clear();
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }
    PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(
        handlerMethod.getMethod(),
        PreAuthorize.class
    );
    if (preAuthorize == null) {
      preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
    }
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    boolean inheritedSchema = handlerMethod.getMethod().getDeclaringClass()
        == org.simplepoint.core.base.controller.BaseController.class
        && "schema".equals(handlerMethod.getMethod().getName());
    if (inheritedSchema) {
      // Inherited metadata has no method-security annotation: enforce its owning page here.
      String page = registry.findControllerPage(handlerMethod.getBeanType())
          .orElseThrow(() -> new AccessDeniedException("无法确定元数据所属页面"));
      requirePageAccess(page, context);
      ResourcePolicyContext.set(page);
      return true;
    }
    Set<String> declaredAuthorities = preAuthorize == null ? Set.of() : extractAuthorities(preAuthorize.value());
    if (declaredAuthorities.isEmpty()) {
      var explicit = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(),
          org.simplepoint.core.permission.PermissionPage.class);
      if (explicit != null) ResourcePolicyContext.set(explicit.value());
      return true;
    }
    if (context == null) return true;

    boolean knownAuthority = false;
    for (String authority : declaredAuthorities) {
      var scopes = registry.findScopes(authority);
      if (scopes.isEmpty()) {
        continue;
      }
      knownAuthority = true;
      if (ResourceScopePolicy.isAccessible(scopes.get(), context)) {
        selectPage(declaredAuthorities, context, handlerMethod.getBeanType());
        return true;
      }
    }
    if (knownAuthority) {
      throw new AccessDeniedException("当前工作空间不可访问该资源");
    }
    Collection<String> contextResources = context.getResources();
    if (contextResources != null && contextResources.stream().anyMatch(declaredAuthorities::contains)) {
      selectPage(declaredAuthorities, context, handlerMethod.getBeanType());
      return true;
    }
    throw new AccessDeniedException("资源未声明或不属于当前工作空间");
  }

  private void requirePageAccess(String page, AuthorizationContext context) {
    var scopes = registry.findScopes(page);
    if (context == null || scopes.isEmpty() || !ResourceScopePolicy.isAccessible(scopes.get(), context))
      throw new AccessDeniedException("当前工作空间不可访问该页面元数据");
    Collection<String> resources = context.getResources();
    boolean granted = resources != null && resources.stream().anyMatch(code -> page.equals(code)
        || registry.findPage(code).filter(page::equals).isPresent());
    if (!Boolean.TRUE.equals(context.getIsAdministrator()) && !granted)
      throw new AccessDeniedException("缺少页面资源权限");
  }

  private void selectPage(Set<String> authorities, AuthorizationContext context, Class<?> controller) {
    if (org.simplepoint.core.base.controller.BaseController.class.isAssignableFrom(controller)) {
      var page = registry.findControllerPage(controller);
      if (page.isPresent()) {
        ResourcePolicyContext.set(page.get());
        return;
      }
    }
    Set<String> pages = new LinkedHashSet<>();
    authorities.forEach(authority -> registry.findPage(authority).ifPresent(pages::add));
    if (pages.size() == 1) {
      ResourcePolicyContext.set(pages.iterator().next());
    } else if (pages.size() > 1 && ((context.getResourceDataScopes() != null
        && !context.getResourceDataScopes().isEmpty())
        || (context.getResourceFieldPermissions() != null
        && !context.getResourceFieldPermissions().isEmpty()))) {
      throw new AccessDeniedException("接口对应多个页面资源，无法确定数据与字段规则");
    }
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
      Object handler, Exception ex) {
    ResourcePolicyContext.clear();
  }

  @Override
  public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
      Object handler) {
    ResourcePolicyContext.clear();
  }

  static Set<String> extractAuthorities(final String expression) {
    Set<String> authorities = new LinkedHashSet<>();
    if (expression == null || expression.isBlank()) {
      return authorities;
    }
    Matcher functionMatcher = AUTHORITY_FUNCTION.matcher(expression);
    while (functionMatcher.find()) {
      Matcher valueMatcher = QUOTED_VALUE.matcher(functionMatcher.group(1));
      while (valueMatcher.find()) {
        authorities.add(valueMatcher.group(1));
      }
    }
    return authorities;
  }
}
