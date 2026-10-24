package org.simplepoint.cloud.oauth.server.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication;
import org.simplepoint.cloud.oauth.server.service.LoginSession;
import org.simplepoint.plugin.rbac.authentication.LoginProtection;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/** Handler for successful login authentication events. 登录认证成功处理器 */
@Slf4j
@Component
public final class LoginAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
  private final AuthenticationSuccessHandler delegate =
      new SavedRequestAwareAuthenticationSuccessHandler();
  private final LoginAuditEventPublisher loginAuditEventPublisher;
  private final UserManagementService usersService;
  private final ExternalIdentityLinkFlow externalIdentityLinkFlow;
  private final LoginProtection protection;

  /** Login Authentication Success Handler. */
  public LoginAuthenticationSuccessHandler(
      final LoginAuditEventPublisher loginAuditEventPublisher,
      final UserManagementService usersService,
      final ExternalIdentityLinkFlow externalIdentityLinkFlow,
      final LoginProtection protection) {
    this.loginAuditEventPublisher = loginAuditEventPublisher;
    this.usersService = usersService;
    this.externalIdentityLinkFlow = externalIdentityLinkFlow;
    this.protection = protection;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    // The framework already persisted the password result. Remove it before any
    // callback/audit can fail, and persist only a restricted challenge or final identity.
    LoginSession.save(request, response, null);
    User currentUser = resolveLocalUser(authentication);
    if (currentUser != null && Boolean.TRUE.equals(currentUser.getTwoFactorEnabled())) {
      LoginSession.save(
          request,
          response,
          new PendingMfaAuthentication(
              currentUser.getId(),
              currentUser.getAuthorizationVersion(),
              java.time.Instant.now().plusSeconds(300)));
      resetInputBudget(request);
      response.sendRedirect(request.getContextPath() + "/two-factor/verify");
      return;
    }
    complete(
        request,
        response,
        currentUser == null ? authentication : LoginSession.authenticated(currentUser));
  }

  /** Called only after all configured factors have been verified. */
  public void complete(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    User currentUser = resolveLocalUser(authentication);
    try {
      if (currentUser != null) {
        externalIdentityLinkFlow.completeAfterLocalLogin(request.getSession(), currentUser);
      }
    } catch (OAuth2AuthenticationException ex) {
      ExternalIdentityLinkFlow.PendingLink pending =
          externalIdentityLinkFlow.pending(request.getSession());
      boolean gateway = pending != null && pending.gateway();
      String prefix = gateway ? "/authorization" : request.getContextPath();
      String query = gateway ? "?gateway=true&error=link_conflict" : "?error=link_conflict";
      response.sendRedirect(prefix + "/external-account/link" + query);
      return;
    }
    ExternalIdentityLinkFlow.RecentlyLinked recentlyLinked =
        externalIdentityLinkFlow.consumeRecentlyLinked(request.getSession());
    if (recentlyLinked != null && recentlyLinked.returnToSettings()) {
      persistSuccess(request, response, authentication);
      String prefix = recentlyLinked.gateway() ? "/authorization" : "";
      String query =
          recentlyLinked.gateway()
              ? "?gateway=true&linked=" + recentlyLinked.registrationId()
              : "?linked=" + recentlyLinked.registrationId();
      response.sendRedirect(prefix + "/account/external-identities" + query);
      return;
    }
    this.onAuthenticationSuccessDelegate(request, response, authentication);
  }

  private void resetInputBudget(HttpServletRequest request) {
    String username = request.getParameter("username");
    if (username != null) {
      protection.passwordInputSucceeded(username);
    }
  }

  private void persistSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    try {
      loginAuditEventPublisher.publishSuccess(request, authentication);
      resetInputBudget(request);
      if (request.getSession(false) != null) {
        request.changeSessionId();
      }
      // Authentication clears the pre-login token. Resolve the active filter's deferred token
      // before redirecting: concurrent first /csrf requests must not create competing tokens.
      // Using the request attribute preserves the configured repository and BREACH masking.
      Object csrf = request.getAttribute(CsrfToken.class.getName());
      if (!(csrf instanceof CsrfToken token)
          || !org.springframework.util.StringUtils.hasText(token.getToken())) {
        throw new IllegalStateException("Browser session protection was not initialized");
      }
      LoginSession.save(request, response, authentication);
    } catch (RuntimeException ex) {
      LoginSession.clear(request, response);
      throw ex;
    }
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
   * <p>将认证成功处理委托给底层处理器
   *
   * @param request the HTTP servlet request HTTP servlet 请求
   * @param response the HTTP servlet response HTTP servlet 响应
   * @param authentication the authentication object 认证对象
   * @throws ServletException if a servlet error occurs 如果发生 servlet 错误
   * @throws IOException if an I/O error occurs 如果发生 I/O 错误
   */
  public void onAuthenticationSuccessDelegate(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws ServletException, IOException {
    persistSuccess(request, response, authentication);
    this.delegate.onAuthenticationSuccess(request, response, authentication);
  }
}
