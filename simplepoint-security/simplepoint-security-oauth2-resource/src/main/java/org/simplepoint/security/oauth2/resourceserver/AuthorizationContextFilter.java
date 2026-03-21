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
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.security.context.AuthorizationContextResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AuthorizationContextFilter 是一个 Servlet 过滤器，用于从 HTTP 请求中提取授权上下文信息，并将其注入到 Spring 的 RequestAttributes 中.
 *
 * <p>注意：这个 Filter 必须在 Spring Security 的 JWT 认证发生之前执行，否则 JwtGrantedAuthoritiesConverter
 * 拿不到 AuthorizationContext。</p>
 */
@Component
public class AuthorizationContextFilter extends OncePerRequestFilter {

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
      // 这里先给一个最小可工作的实现：如果没有 session 就不主动创建。
      // 真实场景建议：从 JWT claim / 远程权限缓存 / header 等解析出 userId、permissions。
      String contextId = request.getHeader("X-Context-Id");
      String tenantId = request.getHeader("X-Tenant-Id");
      if ((contextId != null && !contextId.isBlank()) || (tenantId != null && !tenantId.isBlank())) {
        AuthorizationContext ctx = authorizationContextResolver.load(contextId);
        if (ctx == null) {
          Enumeration<String> headerNames = request.getHeaderNames();
          Map<String, String> headers = new HashMap<>();
          while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
          }
          if (contextId != null && !contextId.isBlank()) {
            headers.put("X-Context-Id", contextId);
          }
          if (tenantId != null && !tenantId.isBlank()) {
            headers.put("X-Tenant-Id", tenantId);
          }
          ctx = authorizationContextResolver.resolve(headers);
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
}
