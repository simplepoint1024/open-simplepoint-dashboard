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
import org.simplepoint.security.ResourceScopePolicy;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces the resource boundary declared by controller authority expressions. */
public class ResourceScopeHandlerInterceptor implements HandlerInterceptor {

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
    if (preAuthorize == null) {
      return true;
    }

    Set<String> declaredAuthorities = extractAuthorities(preAuthorize.value());
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (declaredAuthorities.isEmpty() || context == null) {
      return true;
    }

    boolean knownAuthority = false;
    for (String authority : declaredAuthorities) {
      var scopes = registry.findScopes(authority);
      if (scopes.isEmpty()) {
        continue;
      }
      knownAuthority = true;
      if (ResourceScopePolicy.isAccessible(scopes.get(), context)) {
        return true;
      }
    }
    if (knownAuthority) {
      throw new AccessDeniedException("当前工作空间不可访问该资源");
    }
    Collection<String> contextResources = context.getResources();
    if (contextResources != null && contextResources.stream().anyMatch(declaredAuthorities::contains)) {
      return true;
    }
    throw new AccessDeniedException("资源未声明或不属于当前工作空间");
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
