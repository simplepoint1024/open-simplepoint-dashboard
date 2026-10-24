/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.security.authorization.audit;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.plugin.auditing.logging.api.event.LoginAuditContract;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.LoginLogRecordCommand;
import org.simplepoint.security.authentication.event.LoginAuthenticationFailureEvent;
import org.simplepoint.security.authentication.event.LoginAuthenticationSuccessEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bridges authorization login events into the auditing service. */
@Slf4j
@Component
public class LoginAuthenticationAuditEventListener {

  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILURE = "FAILURE";
  private static final String SOURCE_SERVICE = "authorization";

  private final EventPublisher eventPublisher;

  /** Login Authentication Audit Event Listener. */
  public LoginAuthenticationAuditEventListener(final EventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  /**
   * @ Event Listener.
   */
  @EventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onLoginSuccess(final LoginAuthenticationSuccessEvent event) {
    LoginLogRecordCommand command =
        baseCommand(
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
            event.loginAt());
    command.setStatus(STATUS_SUCCESS);
    record(command);
  }

  /**
   * @ Event Listener.
   */
  @EventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onLoginFailure(final LoginAuthenticationFailureEvent event) {
    LoginLogRecordCommand command =
        baseCommand(
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
            event.loginAt());
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
      final java.time.Instant loginAt) {
    LoginLogRecordCommand command = new LoginLogRecordCommand();
    command.setUserId(normalize(userId));
    command.setUsername(firstNonBlank(username, userId));
    command.setDisplayName(firstNonBlank(displayName, username, userId));
    command.setTenantId(null); // Login has no trusted tenant selection.
    command.setContextId(null);
    command.setSessionId(normalize(sessionId));
    command.setRemoteAddress(normalize(remoteAddress));
    command.setForwardedFor(normalize(forwardedFor));
    command.setClientIp(normalize(remoteAddress));
    command.setUserAgent(normalize(userAgent));
    command.setRequestUri(normalize(requestUri));
    command.setAuthenticationType(normalize(authenticationType));
    command.setLoginType(resolveLoginType(requestUri));
    command.setSourceService(SOURCE_SERVICE);
    command.setLoginAt(loginAt);
    return command;
  }

  private void record(final LoginLogRecordCommand command) {
    var id = java.util.UUID.randomUUID();
    eventPublisher.publish(
        new EventMessage(
            id,
            LoginAuditContract.EVENT_TYPE,
            1,
            "authorization",
            "platform",
            "authentication",
            0,
            id.toString(),
            id.toString(),
            command.getLoginAt(),
            LoginAuditContract.encode(command)));
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
    return trimmed.isEmpty() ? null : trimmed.substring(0, Math.min(trimmed.length(), 128));
  }
}
