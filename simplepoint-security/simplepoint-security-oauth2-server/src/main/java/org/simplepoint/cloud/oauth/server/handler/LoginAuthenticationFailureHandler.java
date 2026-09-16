/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Authentication failure handler that publishes login failure events.
 */
@Component
public class LoginAuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final LoginAuditEventPublisher loginAuditEventPublisher;
  private final ExternalIdentityLinkFlow externalIdentityLinkFlow;

  /**
   * Login Authentication Failure Handler.
   */
  public LoginAuthenticationFailureHandler(
      final LoginAuditEventPublisher loginAuditEventPublisher,
      final ExternalIdentityLinkFlow externalIdentityLinkFlow
  ) {
    this.loginAuditEventPublisher = loginAuditEventPublisher;
    this.externalIdentityLinkFlow = externalIdentityLinkFlow;
  }

  @Override
  public void onAuthenticationFailure(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception
  )
      throws IOException, ServletException {
    loginAuditEventPublisher.publishFailure(request, request.getParameter("username"), exception);
    HttpSession session = request.getSession();
    ExternalIdentityLinkFlow.PendingLink pending = externalIdentityLinkFlow.pending(session);
    String errorCode = errorCode(exception);
    if (pending != null && pending.explicit()) {
      boolean gateway = pending.gateway();
      externalIdentityLinkFlow.clear(session);
      String prefix = gateway ? "/authorization" : "";
      String query = gateway ? "?gateway=true&error=" : "?error=";
      response.sendRedirect(prefix + "/account/external-identities" + query + errorCode);
      return;
    }
    if (pending != null && "external_account_not_linked".equals(errorCode)) {
      String prefix = pending.gateway() ? "/authorization" : "";
      String query = pending.gateway() ? "?gateway=true" : "";
      response.sendRedirect(prefix + "/external-account/link" + query);
      return;
    }
    if (pending != null && !pending.explicit()
        && request.getParameter("username") != null) {
      String prefix = pending.gateway() ? "/authorization" : "";
      String query = pending.gateway()
          ? "?gateway=true&error=invalid_credentials"
          : "?error=invalid_credentials";
      response.sendRedirect(prefix + "/external-account/link" + query);
      return;
    }
    response.sendRedirect("/login?error=" + errorCode);
  }

  private String errorCode(final Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof OAuth2AuthenticationException oauth2Exception
          && oauth2Exception.getError() != null
          && oauth2Exception.getError().getErrorCode() != null) {
        return safeCode(oauth2Exception.getError().getErrorCode());
      }
      current = current.getCause();
    }
    return "invalid_credentials";
  }

  private String safeCode(final String value) {
    String normalized = value.replaceAll("[^a-zA-Z0-9_-]", "");
    return normalized.isBlank() ? "authentication_failed" : normalized;
  }
}
