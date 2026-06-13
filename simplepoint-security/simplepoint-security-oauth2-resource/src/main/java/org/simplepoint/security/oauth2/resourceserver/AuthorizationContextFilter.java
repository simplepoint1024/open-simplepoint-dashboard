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
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AuthorizationContextFilter 是一个 Servlet 过滤器，用于从 HTTP 请求中提取授权上下文信息，并将其注入到 Spring 的 RequestAttributes 中.
 *
 * <p>注意：这个 Filter 必须在 Spring Security 的 JWT 认证发生之前执行，否则 JwtGrantedAuthoritiesConverter
 * 拿不到 AuthorizationContext。</p>
 */
@Component
public class AuthorizationContextFilter extends OncePerRequestFilter {

  private static final String HEADER_CONTEXT_ID = "X-Context-Id";
  private static final String HEADER_TENANT_ID = "X-Tenant-Id";
  private static final String HEADER_USER_ID = "X-User-Id";
  private static final String HEADER_SCOPE_TYPE = "X-Scope-Type";
  private static final String HEADER_ACTOR_ROLE = "X-Actor-Role";
  private static final Set<String> CACHED_CONTEXT_PROTECTED_HEADERS = Set.of(
      HEADER_TENANT_ID,
      HEADER_USER_ID,
      HEADER_SCOPE_TYPE,
      HEADER_ACTOR_ROLE
  );

  private final AuthorizationContextResolver authorizationContextResolver;

  /**
   * Constructs an AuthorizationContextFilter with the specified AuthorizationContextResolver.
   *
   * @param authorizationContextResolver the AuthorizationContextResolver used to resolve the authorization context for incoming requests
   */
  public AuthorizationContextFilter(AuthorizationContextResolver authorizationContextResolver) {
    this.authorizationContextResolver = authorizationContextResolver;
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
  protected void doFilterInternal(
      @Nonnull HttpServletRequest request,
      @Nonnull HttpServletResponse response,
      @Nonnull FilterChain filterChain
  ) throws ServletException, IOException {

    try {
      String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
      String contextId = request.getHeader(HEADER_CONTEXT_ID);
      String tenantId = request.getHeader(HEADER_TENANT_ID);
      if (StringUtils.hasText(authorization)) {
        Map<String, String> headers = collectHeaders(request, contextId, tenantId);
        AuthorizationContext ctx = authorizationContextResolver.load(contextId);
        if (ctx == null) {
          ctx = authorizationContextResolver.resolve(headers);
        } else {
          if (hasTenantMismatch(ctx, tenantId)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorization context tenant mismatch");
            return;
          }
          ctx.mergeAttributes(filterCachedContextHeaders(headers));
        }
        if (ctx != null) {
          RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, ctx);
        }
      }

      filterChain.doFilter(request, response);
    } finally {
      // 避免 request 重用/二次 dispatch 带来脏数据
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    }
  }

  private Map<String, String> collectHeaders(HttpServletRequest request, String contextId, String tenantId) {
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
    return headers;
  }

  private boolean hasTenantMismatch(AuthorizationContext context, String tenantId) {
    String requestedTenantId = normalize(tenantId);
    if (!StringUtils.hasText(requestedTenantId)) {
      return false;
    }
    String cachedTenantId = normalize(context.getAttribute(HEADER_TENANT_ID));
    return StringUtils.hasText(cachedTenantId) && !requestedTenantId.equals(cachedTenantId);
  }

  private Map<String, String> filterCachedContextHeaders(Map<String, String> headers) {
    Map<String, String> safeHeaders = new HashMap<>();
    headers.forEach((key, value) -> {
      if (key != null && !CACHED_CONTEXT_PROTECTED_HEADERS.contains(normalizeHeaderName(key))) {
        safeHeaders.put(key, value);
      }
    });
    return safeHeaders;
  }

  private String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String normalizeHeaderName(String headerName) {
    if (HEADER_TENANT_ID.equalsIgnoreCase(headerName)) {
      return HEADER_TENANT_ID;
    }
    if (HEADER_USER_ID.equalsIgnoreCase(headerName)) {
      return HEADER_USER_ID;
    }
    if (HEADER_SCOPE_TYPE.equalsIgnoreCase(headerName)) {
      return HEADER_SCOPE_TYPE;
    }
    if (HEADER_ACTOR_ROLE.equalsIgnoreCase(headerName)) {
      return HEADER_ACTOR_ROLE;
    }
    return headerName;
  }
}
