package org.simplepoint.cloud.oauth.server.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
  private final AuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();
  private final LoginAuditEventPublisher loginAuditEventPublisher;
  private final UsersService usersService;

  /**
   * Login Authentication Success Handler.
   */
  public LoginAuthenticationSuccessHandler(
      final LoginAuditEventPublisher loginAuditEventPublisher,
      final UsersService usersService
  ) {
    this.loginAuditEventPublisher = loginAuditEventPublisher;
    this.usersService = usersService;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    User currentUser = resolveLocalUser(authentication);
    if (currentUser != null
        && Boolean.TRUE.equals(currentUser.getTwoFactorEnabled())
        && currentUser.getTwoFactorSecret() != null) {
      if (!(authentication.getPrincipal() instanceof User)) {
        UsernamePasswordAuthenticationToken localAuthentication =
            new UsernamePasswordAuthenticationToken(
                currentUser,
                null,
                authentication.getAuthorities()
            );
        localAuthentication.setDetails(authentication.getDetails());
        SecurityContextHolder.getContext().setAuthentication(localAuthentication);
      }
      response.sendRedirect(request.getContextPath() + "/two-factor/verify");
      return;
    }
    this.onAuthenticationSuccessDelegate(request, response, authentication);
  }

  private User resolveLocalUser(final Authentication authentication) {
    if (authentication == null) {
      return null;
    }
    if (authentication.getPrincipal() instanceof User user) {
      return user;
    }
    return usersService.findByIdForAuthorization(authentication.getName()).orElse(null);
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
    loginAuditEventPublisher.publishSuccess(request, authentication);
    this.delegate.onAuthenticationSuccess(request, response, authentication);
  }
}
