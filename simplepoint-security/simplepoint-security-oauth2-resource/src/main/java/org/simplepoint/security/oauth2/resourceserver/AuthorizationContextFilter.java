package org.simplepoint.security.oauth2.resourceserver;

import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AuthorizationContextFilter 是一个 Servlet 过滤器，用于从 HTTP 请求中提取授权上下文信息，并将其注入到 Spring 的 RequestAttributes 中.
 *
 * <p>Runs after bearer-token authentication and before authorization. Cached policy is
 * bound to the verified JWT subject and the current committed tenant version.</p>
 */
public class AuthorizationContextFilter extends OncePerRequestFilter {

  private static final String HEADER_CONTEXT_ID = "X-Context-Id";
  private static final String HEADER_TENANT_ID = "X-Tenant-Id";
  private static final String HEADER_ROLE_ID = "X-Role-Id";
  private static final Set<String> CONTEXT_EXCLUDED_EXACT_PATHS = Set.of(
      "/error"
  );
  private static final Set<String> CONTEXT_EXCLUDED_PATH_PREFIXES = Set.of(
      "/actuator/",
      "/internal/",
      "/static/",
      "/mf/",
      "/v3/api-docs/",
      "/swagger-ui/",
      "/css/",
      "/js/",
      "/images/"
  );

  private final AuthorizationContextResolver authorizationContextResolver;

  private final String serviceRouterExposePath;

  /**
   * Constructs an AuthorizationContextFilter with the specified AuthorizationContextResolver.
   *
   * @param authorizationContextResolver the AuthorizationContextResolver used to resolve the authorization context for incoming requests
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
      AuthorizationContextResolver authorizationContextResolver,
      String serviceRouterExposePath
  ) {
    this.authorizationContextResolver = authorizationContextResolver;
    this.serviceRouterExposePath = serviceRouterExposePath;
  }

  /**
   * If your app uses async dispatch, you may want to set this to true.
   * For now, keep default behaviour and avoid double-setting on ERROR dispatch.
   */
  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return true;
  }

  @Override
  protected boolean shouldNotFilter(@Nonnull HttpServletRequest request) {
    String path = requestPath(request);
    return CONTEXT_EXCLUDED_EXACT_PATHS.contains(path)
        || path.equals(serviceRouterExposePath)
        || CONTEXT_EXCLUDED_PATH_PREFIXES.stream().anyMatch(path::startsWith);
  }

  @Override
  protected void doFilterInternal(
      @Nonnull HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain
  ) throws ServletException, IOException {

    try {
      String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
      String contextId = request.getHeader(HEADER_CONTEXT_ID);
      String tenantId = request.getHeader(HEADER_TENANT_ID);
      String roleId = request.getHeader(HEADER_ROLE_ID);
      if (StringUtils.hasText(authorization)) {
        Map<String, String> headers = collectHeaders(request, contextId, tenantId, roleId);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
          throw new org.springframework.security.authentication.BadCredentialsException("Bearer token is not authenticated");
        }
        AuthorizationContext ctx = authorizationContextResolver.resolveAuthenticated(jwt.getToken().getSubject(), headers);
        if (ctx == null) {
          throw new AccessDeniedException("Authorization context is required");
        }
        RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, ctx);
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(jwt.getAuthorities());
        authorities.addAll(ctx.asAuthorities());
        JwtAuthenticationToken scoped = new JwtAuthenticationToken(jwt.getToken(), authorities, jwt.getName());
        scoped.setDetails(jwt.getDetails());
        SecurityContextHolder.getContext().setAuthentication(scoped);
      }

      filterChain.doFilter(request, response);
    } catch (AuthenticationException ex) {
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, ex);
    } catch (AccessDeniedException ex) {
      sendError(response, HttpServletResponse.SC_FORBIDDEN, ex);
    } finally {
      // 避免 request 重用/二次 dispatch 带来脏数据
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private void sendError(HttpServletResponse response, int status, RuntimeException ex) throws IOException {
    if (!response.isCommitted()) {
      response.sendError(status, ex.getMessage());
    }
  }

  private Map<String, String> collectHeaders(HttpServletRequest request, String contextId, String tenantId, String roleId) {
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
