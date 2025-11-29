package org.simplepoint.cloud.oauth.server.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.security.cache.AuthorizationContextCacheable;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/**
 * Handler for successful login authentication events.
 * 登录认证成功处理器
 */
@Slf4j
public final class LoginAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
  private final AuthorizationContextCacheable authorizationContextCacheable;
  private final AuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();

  /**
   * Constructs a LoginAuthenticationSuccessHandler with the specified
   * AuthorizationContextCacheable.
   *
   * <p>使用指定的 AuthorizationContextCacheable 构造 LoginAuthenticationSuccessHandler</p>
   *
   * @param authorizationContextCacheable the authorization context cacheable
   *                                      授权上下文缓存接口
   */
  public LoginAuthenticationSuccessHandler(AuthorizationContextCacheable authorizationContextCacheable) {
    this.authorizationContextCacheable = authorizationContextCacheable;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    Object details = authentication.getPrincipal();
    if (details != null) {
      if (details instanceof User currentUser) {
        String username = currentUser.getUsername();
        // Cache the authorization context for the authenticated user
        // 缓存已认证用户的授权上下文
        this.authorizationContextCacheable.cacheUserPermission(username, currentUser.getPermissions());

        this.authorizationContextCacheable.cacheUserContext(username, currentUser);
        log.debug("Cached authorization context for user [{}]", username);
      }
    }
    this.delegate.onAuthenticationSuccess(request, response, authentication);
  }

}
