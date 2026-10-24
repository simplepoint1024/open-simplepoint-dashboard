package org.simplepoint.security.oauth2.resourceserver.composition;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.simplepoint.cache.CacheService;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.oauth2.resourceserver.AuthorizationContextFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Static route boundaries contain independently bound clients; requests cannot supply a caller. */
public final class ModuleAuthorizationContexts {
  private final OncePerRequestFilter requestFilter =
      new OncePerRequestFilter() {
        @Override
        protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
          filterRequest(request, response, chain);
        }
      };
  private final Map<String, Boundary> boundaries;
  private final CacheService cache;
  private final String rpcPath;

  /** Prepares only selected business adapters and rejects missing authorization contributions. */
  public ModuleAuthorizationContexts(ModulePlan modules, CacheService cache, String rpcPath) {
    this.cache = cache;
    this.rpcPath = rpcPath;
    var http = ModuleHttpPlan.from(modules);
    var result = new LinkedHashMap<String, Boundary>();
    for (var module : modules.modules()) {
      var declaration = module.configuration().getDeclaredAnnotation(ModuleHttp.class);
      if (declaration == null || !declaration.scopedAuthorization()) {
        continue;
      }
      if (!module.initializers().contains(ModuleAuthorizationInitializer.class)) {
        throw new ModuleCompositionException(
            "MISSING_HTTP_AUTHORIZATION_CONTRIBUTION", module.id());
      }
      var resolver = new AtomicReference<AuthorizationContextResolver>();
      String prefix = declaration.namespace().equals("/") ? "" : declaration.namespace();
      var filter =
          new AuthorizationContextFilter(
              () -> {
                var bound = resolver.get();
                if (bound == null) {
                  throw new ModuleCompositionException(
                      "HTTP_AUTHORIZATION_NOT_PREPARED", module.id());
                }
                return bound;
              },
              rpcPath,
              prefix);
      result.put(
          module.id(), new Boundary(http.routes().get(module.id()), resolver, filter, prefix));
    }
    boundaries = Collections.unmodifiableMap(result);
  }

  void bind(String module, AuthorizationContextService client) {
    var boundary = boundaries.get(module);
    if (boundary == null) {
      throw new ModuleCompositionException("UNOWNED_HTTP_AUTHORIZATION", module);
    }
    var resolver = AuthorizationContextResolver.forAuthenticatedSubjects(cache, client);
    if (!boundary.resolver().compareAndSet(null, resolver)) {
      throw new ModuleCompositionException("DUPLICATE_HTTP_AUTHORIZATION", module);
    }
  }

  /** Returns the security-chain filter without registering a global servlet Filter bean. */
  public jakarta.servlet.Filter filter() {
    return requestFilter;
  }

  /** Creates a separate filter for an explicitly authenticated, CSRF-protected session chain. */
  public jakarta.servlet.Filter browserFilter(
      java.util.function.Function<org.springframework.security.core.Authentication, String>
          subject) {
    java.util.Objects.requireNonNull(subject);
    var filters = new LinkedHashMap<Boundary, AuthorizationContextFilter>();
    boundaries
        .values()
        .forEach(
            boundary ->
                filters.put(
                    boundary,
                    new AuthorizationContextFilter(
                        () -> {
                          var resolver = boundary.resolver().get();
                          if (resolver == null) {
                            throw new ModuleCompositionException(
                                "HTTP_AUTHORIZATION_NOT_PREPARED", "browser");
                          }
                          return resolver;
                        },
                        rpcPath,
                        boundary.prefix(),
                        subject)));
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (var entry : filters.entrySet()) {
          if (entry.getKey().roots().stream()
              .anyMatch(root -> ModuleHttpPlan.contains(root, path))) {
            entry.getValue().doFilter(request, response, chain);
            return;
          }
        }
        chain.doFilter(request, response);
      }
    };
  }

  /** Matches only selected business routes; internal/protocol/static routes retain their chains. */
  public boolean matchesBrowserRequest(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    for (var boundary : boundaries.values()) {
      if (boundary.roots().stream().anyMatch(root -> ModuleHttpPlan.contains(root, path))) {
        return boundary.filter().requiresAuthorization(request);
      }
    }
    return false;
  }

  private void filterRequest(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    for (var boundary : boundaries.values()) {
      if (boundary.roots().stream().anyMatch(root -> ModuleHttpPlan.contains(root, path))) {
        boundary.filter().doFilter(request, response, chain);
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private record Boundary(
      Set<String> roots,
      AtomicReference<AuthorizationContextResolver> resolver,
      AuthorizationContextFilter filter,
      String prefix) {}
}
