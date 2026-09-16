/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.configuration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Redirects unauthenticated browser requests without exposing an internal service address.
 */
final class GatewayAwareLoginAuthenticationEntryPoint implements AuthenticationEntryPoint {

  static final String GATEWAY_LOGIN_LOCATION = "/authorization/login?gateway=true";

  private static final String DIRECT_LOGIN_LOCATION = "/login";

  private static final String FORWARDED_PREFIX_HEADER = "X-Forwarded-Prefix";

  private static final String AUTHORIZATION_GATEWAY_PREFIX = "/authorization";

  @Override
  public void commence(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception
  ) throws IOException {
    String location = isGatewayRequest(request)
        ? GATEWAY_LOGIN_LOCATION
        : request.getContextPath() + DIRECT_LOGIN_LOCATION;
    response.setStatus(HttpStatus.FOUND.value());
    response.setHeader(HttpHeaders.LOCATION, location);
  }

  private boolean isGatewayRequest(final HttpServletRequest request) {
    if (Boolean.parseBoolean(request.getParameter("gateway"))) {
      return true;
    }
    String forwardedPrefix = request.getHeader(FORWARDED_PREFIX_HEADER);
    if (forwardedPrefix == null) {
      return false;
    }
    for (String value : forwardedPrefix.split(",")) {
      if (AUTHORIZATION_GATEWAY_PREFIX.equals(value.trim())) {
        return true;
      }
    }
    return false;
  }
}
