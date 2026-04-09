/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.authorization.server.listener;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.event.LoginAuthenticationFailureEvent;
import org.simplepoint.cloud.oauth.server.event.LoginAuthenticationSuccessEvent;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.LoginLogRemoteService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges authorization login events into the auditing service.
 */
@Slf4j
@Component
public class LoginAuthenticationAuditEventListener {

  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILURE = "FAILURE";
  private static final String SOURCE_SERVICE = "authorization";
  private static final String DEFAULT_TENANT_ID = "default";

  private final LoginLogRemoteService loginLogRemoteService;

  public LoginAuthenticationAuditEventListener(final LoginLogRemoteService loginLogRemoteService) {
    this.loginLogRemoteService = loginLogRemoteService;
  }

  @EventListener
  public void onLoginSuccess(final LoginAuthenticationSuccessEvent event) {
    LoginLogRecordCommand command = baseCommand(
        event.userId(),
        event.username(),
        event.displayName(),
        event.tenantId(),
        event.contextId(),
        event.sessionId(),
        event.remoteAddress(),
        event.forwardedFor(),
        event.userAgent(),
        event.requestUri(),
        event.authenticationType(),
        event.loginAt()
    );
    command.setStatus(STATUS_SUCCESS);
    record(command);
  }

  @EventListener
  public void onLoginFailure(final LoginAuthenticationFailureEvent event) {
    LoginLogRecordCommand command = baseCommand(
        event.userId(),
        event.username(),
        event.displayName(),
        event.tenantId(),
        event.contextId(),
        event.sessionId(),
        event.remoteAddress(),
        event.forwardedFor(),
        event.userAgent(),
        event.requestUri(),
        event.authenticationType(),
        event.loginAt()
    );
    command.setStatus(STATUS_FAILURE);
    command.setFailureReason(event.failureReason());
    record(command);
  }

  private LoginLogRecordCommand baseCommand(
      final String userId,
      final String username,
      final String displayName,
      final String tenantId,
      final String contextId,
      final String sessionId,
      final String remoteAddress,
      final String forwardedFor,
      final String userAgent,
      final String requestUri,
      final String authenticationType,
      final java.time.Instant loginAt
  ) {
    LoginLogRecordCommand command = new LoginLogRecordCommand();
    command.setUserId(normalize(userId));
    command.setUsername(firstNonBlank(username, userId));
    command.setDisplayName(firstNonBlank(displayName, username, userId));
    command.setTenantId(firstNonBlank(tenantId, DEFAULT_TENANT_ID));
    command.setContextId(normalize(contextId));
    command.setSessionId(normalize(sessionId));
    command.setRemoteAddress(normalize(remoteAddress));
    command.setForwardedFor(normalize(forwardedFor));
    command.setClientIp(resolveClientIp(forwardedFor, remoteAddress));
    command.setUserAgent(normalize(userAgent));
    command.setRequestUri(normalize(requestUri));
    command.setAuthenticationType(normalize(authenticationType));
    command.setLoginType(resolveLoginType(requestUri));
    command.setSourceService(SOURCE_SERVICE);
    command.setLoginAt(loginAt);
    return command;
  }

  private void record(final LoginLogRecordCommand command) {
    try {
      loginLogRemoteService.record(command);
    } catch (RuntimeException ex) {
      log.error("Failed to record login log for user [{}] and request [{}]", command.getUsername(), command.getRequestUri(), ex);
    }
  }

  private String resolveLoginType(final String requestUri) {
    String normalizedRequestUri = normalize(requestUri);
    if (normalizedRequestUri != null && normalizedRequestUri.contains("/two-factor/verify")) {
      return "TWO_FACTOR";
    }
    return "PASSWORD";
  }

  private String resolveClientIp(final String forwardedFor, final String remoteAddress) {
    String normalizedForwardedFor = normalize(forwardedFor);
    if (normalizedForwardedFor != null) {
      String[] candidates = normalizedForwardedFor.split(",");
      if (candidates.length > 0) {
        String firstCandidate = normalize(candidates[0]);
        if (firstCandidate != null) {
          return firstCandidate;
        }
      }
    }
    return normalize(remoteAddress);
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
}
