package org.simplepoint.cloud.oauth.server.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationFailureHandler;
import org.simplepoint.plugin.rbac.authentication.LoginProtection;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Unknown account attempts are limited too; raw forwarded headers are never trusted here. */
public final class LoginAttemptFilter extends OncePerRequestFilter {
  private final LoginProtection protection;
  private final LoginAuthenticationFailureHandler failureHandler;

  /** Binds attempt enforcement to the existing login failure and audit path. */
  public LoginAttemptFilter(
      LoginProtection protection, LoginAuthenticationFailureHandler failureHandler) {
    this.protection = protection;
    this.failureHandler = failureHandler;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if ("POST".equals(request.getMethod())
        && (request.getContextPath() + "/login").equals(request.getRequestURI())) {
      try {
        protection.reservePasswordInput(request.getParameter("username"));
      } catch (AuthenticationException ex) {
        failureHandler.onAuthenticationFailure(request, response, ex);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
