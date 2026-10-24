package org.simplepoint.security.oauth2.resourceserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AuthorizationContextFilter 是一个 Servlet 过滤器，用于从 HTTP 请求中提取授权上下文信息，并将其注入到 Spring 的
 * RequestAttributes 中.
 *
 * <p>Runs after bearer-token authentication and before authorization. Cached policy is bound to the
 * verified JWT subject and the current committed tenant version.
 */
public class AuthorizationContextFilter extends OncePerRequestFilter {

  private static final String HEADER_CONTEXT_ID = "X-Context-Id";
  private static final ObjectMapper ERROR_JSON = new ObjectMapper();
  private static final String HEADER_TENANT_ID = "X-Tenant-Id";
  private static final String HEADER_ROLE_ID = "X-Role-Id";
  private static final Set<String> CONTEXT_EXCLUDED_EXACT_PATHS = Set.of("/error");
  private static final Set<String> CONTEXT_EXCLUDED_PATH_PREFIXES =
      Set.of(
          "/actuator/",
          "/internal/",
          "/static/",
          "/mf/",
          "/v3/api-docs/",
          "/swagger-ui/",
          "/css/",
          "/js/",
          "/images/");

  private final java.util.function.Supplier<AuthorizationContextResolver>
      authorizationContextResolver;
  private final String modulePrefix;
  private final java.util.function.Function<
          org.springframework.security.core.Authentication, String>
      sessionSubject;

  private final String serviceRouterExposePath;

  /**
   * Constructs an AuthorizationContextFilter with the specified AuthorizationContextResolver.
   *
   * @param authorizationContextResolver the AuthorizationContextResolver used to resolve the
   *     authorization context for incoming requests
   */
  public AuthorizationContextFilter(AuthorizationContextResolver authorizationContextResolver) {
    this(authorizationContextResolver, "/_simplepoint/service-router/invoke");
  }

  /**
   * Constructs an AuthorizationContextFilter with the specified AuthorizationContextResolver.
   *
   * @param authorizationContextResolver the resolver used to resolve the authorization context
   * @param serviceRouterExposePath internal service-router invocation path
   */
  public AuthorizationContextFilter(
      AuthorizationContextResolver authorizationContextResolver, String serviceRouterExposePath) {
    this(() -> authorizationContextResolver, serviceRouterExposePath, "");
  }

  /** Creates a fixed owning-module boundary; preparation must bind its client before readiness. */
  public AuthorizationContextFilter(
      java.util.function.Supplier<AuthorizationContextResolver> resolver,
      String serviceRouterExposePath,
      String modulePrefix) {
    this(resolver, serviceRouterExposePath, modulePrefix, null);
  }

  /** Session mode is installed only inside a CSRF-protected, revalidating browser boundary. */
  public AuthorizationContextFilter(
      java.util.function.Supplier<AuthorizationContextResolver> resolver,
      String serviceRouterExposePath,
      String modulePrefix,
      java.util.function.Function<org.springframework.security.core.Authentication, String>
          sessionSubject) {
    this.sessionSubject = sessionSubject;
    this.authorizationContextResolver = java.util.Objects.requireNonNull(resolver);
    this.serviceRouterExposePath = serviceRouterExposePath;
    this.modulePrefix = java.util.Objects.requireNonNull(modulePrefix);
  }

  /**
   * If your app uses async dispatch, you may want to set this to true. For now, keep default
   * behaviour and avoid double-setting on ERROR dispatch.
   */
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return true;
  }

  /** Allows the owning browser chain to exclude the same infrastructure routes. */
  public boolean requiresAuthorization(HttpServletRequest request) {
    return !shouldNotFilter(request);
  }

  @Override
  protected boolean shouldNotFilter(@Nonnull HttpServletRequest request) {
    String path = requestPath(request);
    if (!modulePrefix.isEmpty() && path.startsWith(modulePrefix + "/")) {
      path = path.substring(modulePrefix.length());
    }
    final String relativePath = path;
    return CONTEXT_EXCLUDED_EXACT_PATHS.contains(path)
        || path.equals(serviceRouterExposePath)
        || CONTEXT_EXCLUDED_PATH_PREFIXES.stream().anyMatch(relativePath::startsWith);
  }

  @Override
  protected void doFilterInternal(
      @Nonnull HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain)
      throws ServletException, IOException {

    var previousContext = SecurityContextHolder.getContext();
    try {
      String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
      String contextId = request.getHeader(HEADER_CONTEXT_ID);
      String tenantId = request.getHeader(HEADER_TENANT_ID);
      String roleId = request.getHeader(HEADER_ROLE_ID);
      if (sessionSubject != null || StringUtils.hasText(authorization)) {
        Map<String, String> headers = collectHeaders(request, contextId, tenantId, roleId);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String subject;
        if (sessionSubject != null) {
          subject = sessionSubject.apply(authentication);
        } else if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
          subject = jwt.getToken().getSubject();
        } else {
          throw new org.springframework.security.authentication.BadCredentialsException(
              "Bearer token is not authenticated");
        }
        AuthorizationContext ctx =
            authorizationContextResolver.get().resolveAuthenticated(subject, headers);
        if (ctx == null) {
          throw new AccessDeniedException("Authorization context is required");
        }
        RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, ctx);
        org.springframework.security.core.Authentication scoped;
        if (authentication instanceof JwtAuthenticationToken jwt && sessionSubject == null) {
          Set<GrantedAuthority> authorities = new LinkedHashSet<>(jwt.getAuthorities());
          authorities.addAll(ctx.asAuthorities());
          var token = new JwtAuthenticationToken(jwt.getToken(), authorities, jwt.getName());
          token.setDetails(jwt.getDetails());
          scoped = token;
        } else {
          // Do not retain stale session grants or write request-scoped grants into the session.
          var token =
              org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                  .authenticated(authentication.getPrincipal(), null, ctx.asAuthorities());
          token.setDetails(authentication.getDetails());
          scoped = token;
        }
        var scopedContext = SecurityContextHolder.createEmptyContext();
        scopedContext.setAuthentication(scoped);
        SecurityContextHolder.setContext(scopedContext);
      }

      filterChain.doFilter(request, response);
    } catch (AuthenticationException ex) {
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED);
    } catch (AccessDeniedException ex) {
      sendError(response, HttpServletResponse.SC_FORBIDDEN);
    } finally {
      SecurityContextHolder.setContext(previousContext);
      // 避免 request 重用/二次 dispatch 带来脏数据
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private void sendError(HttpServletResponse response, int status) throws IOException {
    if (!response.isCommitted()) {
      final boolean denied = status == HttpServletResponse.SC_FORBIDDEN;
      response.setStatus(status);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      ERROR_JSON.writeValue(
          response.getWriter(),
          Map.of(
              "code", denied ? "SECURITY_ACCESS_DENIED" : "SECURITY_AUTHENTICATION_REQUIRED",
              "messageKey",
                  denied
                      ? "common.error.authorizationDenied"
                      : "common.error.authenticationRequired",
              "message", denied ? "Access denied" : "Authentication is required"));
    }
  }

  private Map<String, String> collectHeaders(
      HttpServletRequest request, String contextId, String tenantId, String roleId) {
    Enumeration<String> headerNames = request.getHeaderNames();
    Map<String, String> headers = new HashMap<>();
    while (headerNames.hasMoreElements()) {
      String headerName = headerNames.nextElement();
      headers.put(headerName, request.getHeader(headerName));
    }
    if (contextId != null && !contextId.isBlank()) {
      headers.put(HEADER_CONTEXT_ID, contextId);
    }
    if (tenantId != null && !tenantId.isBlank()) {
      headers.put(HEADER_TENANT_ID, tenantId);
    }
    if (roleId != null && !roleId.isBlank()) {
      headers.put(HEADER_ROLE_ID, roleId);
    }
    return headers;
  }

  private String requestPath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (StringUtils.hasText(servletPath)) {
      return servletPath;
    }
    String requestUri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)) {
      return requestUri.substring(contextPath.length());
    }
    return requestUri;
  }
}
