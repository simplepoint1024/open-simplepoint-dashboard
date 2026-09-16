/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.util.StringUtils;

/**
 * Recovers browser logout when a previously issued ID token can no longer be validated.
 */
public final class RecoveringOidcLogoutFailureHandler implements AuthenticationFailureHandler {

  private static final String CLIENT_ID = "client_id";

  private static final String ID_TOKEN_HINT = "id_token_hint";

  private static final String POST_LOGOUT_REDIRECT_URI = "post_logout_redirect_uri";

  private final RegisteredClientRepository registeredClientRepository;

  private final AuthenticationFailureHandler errorHandler =
      new OAuth2ErrorAuthenticationFailureHandler();

  private final LogoutHandler logoutHandler = new CompositeLogoutHandler(
      new SecurityContextLogoutHandler(),
      new CookieClearingLogoutHandler("JSESSIONID", "SESSION")
  );

  /**
   * Creates a failure handler with registered-client redirect validation.
   *
   * @param registeredClientRepository registered OIDC clients
   */
  public RecoveringOidcLogoutFailureHandler(
      final RegisteredClientRepository registeredClientRepository
  ) {
    this.registeredClientRepository = registeredClientRepository;
  }

  @Override
  public void onAuthenticationFailure(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception
  ) throws IOException, ServletException {
    String clientId = request.getParameter(CLIENT_ID);
    String idTokenHint = request.getParameter(ID_TOKEN_HINT);
    String redirectUri = request.getParameter(POST_LOGOUT_REDIRECT_URI);
    RegisteredClient registeredClient = findRegisteredClient(clientId);

    if (registeredClient == null
        || !StringUtils.hasText(idTokenHint)
        || !StringUtils.hasText(redirectUri)
        || !registeredClient.getPostLogoutRedirectUris().contains(redirectUri)) {
      errorHandler.onAuthenticationFailure(request, response, exception);
      return;
    }

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    logoutHandler.logout(request, response, authentication);
    response.sendRedirect(redirectUri);
  }

  private RegisteredClient findRegisteredClient(final String clientId) {
    if (!StringUtils.hasText(clientId)) {
      return null;
    }
    try {
      return registeredClientRepository.findByClientId(clientId);
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
