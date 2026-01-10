package org.simplepoint.cloud.oauth.server.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.authority.PermissionGrantedAuthority;
import org.simplepoint.core.authority.RoleGrantedAuthority;
import org.simplepoint.security.cache.AuthorizationContextCacheable;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Handler for successful login authentication events.
 * 登录认证成功处理器
 */
@Slf4j
@Component
public final class LoginAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
  private final AuthorizationContextCacheable authorizationContextCacheable;
  private final AuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();

  /**
   * Constructs a LoginAuthenticationSuccessHandler with the specified dependencies.
   *
   * <p>使用指定的依赖项构造 LoginAuthenticationSuccessHandler</p>
   *
   * @param authorizationContextCacheable the authorization context cacheable
   *                                      授权上下文缓存
   */
  public LoginAuthenticationSuccessHandler(
      AuthorizationContextCacheable authorizationContextCacheable
  ) {
    this.authorizationContextCacheable = authorizationContextCacheable;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    Object details = authentication.getPrincipal();
    if (details != null) {
      if (details instanceof User currentUser) {
        // If user has two-factor enabled, redirect to 2FA verification page
        if (Boolean.TRUE.equals(currentUser.getTwoFactorEnabled()) && currentUser.getTwoFactorSecret() != null) {
          // keep authentication in context, but force user to complete 2FA before accessing protected resources
          response.sendRedirect(request.getContextPath() + "/two-factor/verify");
          return;
        }
      }
    }
    this.onAuthenticationSuccessDelegate(request, response, authentication);
  }

  /**
   * Delegates the authentication success handling to the underlying handler.
   *
   * <p>将认证成功处理委托给底层处理器</p>
   *
   * @param request        the HTTP servlet request
   *                       HTTP servlet 请求
   * @param response       the HTTP servlet response
   *                       HTTP servlet 响应
   * @param authentication the authentication object
   *                       认证对象
   * @throws ServletException if a servlet error occurs
   *                          如果发生 servlet 错误
   * @throws IOException      if an I/O error occurs
   *                          如果发生 I/O 错误
   */
  public void onAuthenticationSuccessDelegate(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws ServletException, IOException {
    Object details = authentication.getPrincipal();
    if (details != null) {
      if (details instanceof User currentUser) {
        String username = currentUser.getUsername();
        var roles = new HashSet<RoleGrantedAuthority>();
        var rolePermissions = new HashMap<String, Set<PermissionGrantedAuthority>>();
        // 根据用户角色缓存权限
        for (GrantedAuthority authority : currentUser.getAuthorities()) {
          if (authority instanceof PermissionGrantedAuthority permissionGrantedAuthority) {
            String role = permissionGrantedAuthority.getRoleAuthority();
            rolePermissions.computeIfAbsent(role, k -> new HashSet<>());
            rolePermissions.get(role).add(permissionGrantedAuthority);
          } else if (authority instanceof RoleGrantedAuthority roleGrantedAutority) {
            roles.add(roleGrantedAutority);
          }
        }

        // 缓存用户角色
        this.authorizationContextCacheable.cacheRoles(username, roles);

        // 缓存用户权限
        rolePermissions.forEach(this.authorizationContextCacheable::cachePermission);

        // 缓存用户上下文
        this.authorizationContextCacheable.cacheUserContext(username, currentUser);
        log.debug("Cached authorization context for user [{}]", username);
      }
    }
    this.delegate.onAuthenticationSuccess(request, response, authentication);

  }
}
