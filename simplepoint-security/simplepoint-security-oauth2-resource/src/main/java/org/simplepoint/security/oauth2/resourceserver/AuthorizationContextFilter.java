package org.simplepoint.security.oauth2.resourceserver;

import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.simplepoint.core.AuthorizationContext;
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

  private final HttpServletRequest servletRequest;

  /**
   * Constructs an AuthorizationContextFilter with the specified AuthorizationContextResolver.
   *
   * @param authorizationContextResolver the AuthorizationContextResolver used to resolve the authorization context for incoming requests
   */
  public AuthorizationContextFilter(AuthorizationContextResolver authorizationContextResolver, HttpServletRequest servletRequest) {
    this.authorizationContextResolver = authorizationContextResolver;
    this.servletRequest = servletRequest;
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
      HttpSession session = request.getSession();
      String contextId = session != null ? session.getId() : null;
      if (contextId != null && !contextId.isBlank()) {
        AuthorizationContext ctx = authorizationContextResolver.load(contextId);
        if (ctx == null) {
          Enumeration<String> headerNames = request.getHeaderNames();
          Map<String, String> headers = new HashMap<>();
          while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
          }
          headers.put("X-Context-Id", contextId);
          headers.put("X-Tenant-Id", "default");
          ctx = authorizationContextResolver.resolve(headers);
        }
        ServletAuthorizationContextHolder.set(ctx);
      }

      filterChain.doFilter(request, response);
    } finally {
      // 避免 request 重用/二次 dispatch 带来脏数据
      ServletAuthorizationContextHolder.clear();
    }
  }
}
