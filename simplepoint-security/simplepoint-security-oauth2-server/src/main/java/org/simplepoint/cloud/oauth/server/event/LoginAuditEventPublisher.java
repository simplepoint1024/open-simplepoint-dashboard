/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.event;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.simplepoint.security.entity.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Publishes login audit events from the authentication flow.
 */
@Component
public class LoginAuditEventPublisher {

  private final ApplicationEventPublisher applicationEventPublisher;

  public LoginAuditEventPublisher(final ApplicationEventPublisher applicationEventPublisher) {
    this.applicationEventPublisher = applicationEventPublisher;
  }

  public void publishSuccess(final HttpServletRequest request, final Authentication authentication) {
    UserIdentity userIdentity = resolveUserIdentity(authentication, null);
    RequestMetadata requestMetadata = resolveRequestMetadata(request);
    applicationEventPublisher.publishEvent(new LoginAuthenticationSuccessEvent(
        userIdentity.userId(),
        userIdentity.username(),
        userIdentity.displayName(),
        requestMetadata.tenantId(),
        requestMetadata.contextId(),
        requestMetadata.sessionId(),
        requestMetadata.remoteAddress(),
        requestMetadata.forwardedFor(),
        requestMetadata.userAgent(),
        requestMetadata.requestUri(),
        authentication == null ? null : authentication.getClass().getSimpleName(),
        Instant.now()
    ));
  }

  public void publishFailure(
      final HttpServletRequest request,
      final Authentication authentication,
      final AuthenticationException exception
  ) {
    UserIdentity userIdentity = resolveUserIdentity(authentication, null);
    publishFailure(request, userIdentity, authentication == null ? null : authentication.getClass().getSimpleName(), exception);
  }

  public void publishFailure(
      final HttpServletRequest request,
      final String username,
      final AuthenticationException exception
  ) {
    UserIdentity userIdentity = resolveUserIdentity(null, username);
    publishFailure(request, userIdentity, null, exception);
  }

  private void publishFailure(
      final HttpServletRequest request,
      final UserIdentity userIdentity,
      final String authenticationType,
      final AuthenticationException exception
  ) {
    RequestMetadata requestMetadata = resolveRequestMetadata(request);
    applicationEventPublisher.publishEvent(new LoginAuthenticationFailureEvent(
        userIdentity.userId(),
        userIdentity.username(),
        userIdentity.displayName(),
        requestMetadata.tenantId(),
        requestMetadata.contextId(),
        requestMetadata.sessionId(),
        requestMetadata.remoteAddress(),
        requestMetadata.forwardedFor(),
        requestMetadata.userAgent(),
        requestMetadata.requestUri(),
        authenticationType,
        resolveFailureReason(exception),
        Instant.now()
    ));
  }

  private UserIdentity resolveUserIdentity(final Authentication authentication, final String explicitUsername) {
    if (authentication == null) {
      String normalizedUsername = normalize(explicitUsername);
      return new UserIdentity(null, normalizedUsername, normalizedUsername);
    }

    Object principal = authentication.getPrincipal();
    if (principal instanceof User user) {
      String username = firstNonBlank(
          user.getEmail(),
          user.getPhoneNumber(),
          user.getPreferredUsername(),
          explicitUsername,
          user.getUsername(),
          user.getId()
      );
      String displayName = firstNonBlank(
          user.getName(),
          user.getNickname(),
          user.getGivenName(),
          username,
          user.getId()
      );
      return new UserIdentity(user.getId(), username, displayName);
    }

    String username = firstNonBlank(authentication.getName(), explicitUsername);
    return new UserIdentity(null, username, username);
  }

  private RequestMetadata resolveRequestMetadata(final HttpServletRequest request) {
    return new RequestMetadata(
        request.getSession(false) == null ? null : request.getSession(false).getId(),
        normalize(request.getHeader("X-Tenant-Id")),
        normalize(request.getHeader("X-Context-Id")),
        normalize(request.getRemoteAddr()),
        normalize(request.getHeader("X-Forwarded-For")),
        normalize(request.getHeader("User-Agent")),
        normalize(request.getRequestURI())
    );
  }

  private String resolveFailureReason(final AuthenticationException exception) {
    if (exception == null) {
      return null;
    }
    return firstNonBlank(exception.getMessage(), exception.getClass().getSimpleName());
  }

  private String firstNonBlank(final String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String normalized = normalize(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record UserIdentity(String userId, String username, String displayName) {
  }

  private record RequestMetadata(
      String sessionId,
      String tenantId,
      String contextId,
      String remoteAddress,
      String forwardedFor,
      String userAgent,
      String requestUri
  ) {
  }
}
