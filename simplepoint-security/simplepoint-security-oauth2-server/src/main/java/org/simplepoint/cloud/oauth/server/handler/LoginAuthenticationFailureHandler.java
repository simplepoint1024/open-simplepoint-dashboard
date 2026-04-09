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
import java.io.IOException;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Authentication failure handler that publishes login failure events.
 */
@Component
public class LoginAuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final AuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler("/login?error");
  private final LoginAuditEventPublisher loginAuditEventPublisher;

  public LoginAuthenticationFailureHandler(final LoginAuditEventPublisher loginAuditEventPublisher) {
    this.loginAuditEventPublisher = loginAuditEventPublisher;
  }

  @Override
  public void onAuthenticationFailure(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception
  )
      throws IOException, ServletException {
    loginAuditEventPublisher.publishFailure(request, request.getParameter("username"), exception);
    delegate.onAuthenticationFailure(request, response, exception);
  }
}
