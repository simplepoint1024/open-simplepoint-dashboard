/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.security.entity.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class LoginAuditEventPublisherTest {

  @Mock
  private ApplicationEventPublisher applicationEventPublisher;

  @InjectMocks
  private LoginAuditEventPublisher publisher;

  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.setRequestURI("/oauth2/token");
  }

  // ── publishSuccess ────────────────────────────────────────────────────────

  @Test
  void publishSuccessShouldPublishEventWithUserPrincipal() {
    User user = mockUser("user-uuid-1", "user@example.com", null, null, "John Doe", null, null);
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationSuccessEvent event = captor.getValue();
    assertEquals("user-uuid-1", event.userId());
    assertEquals("user@example.com", event.username());
    assertEquals("John Doe", event.displayName());
    assertNotNull(event.loginAt());
  }

  @Test
  void publishSuccessShouldUsePhoneNumberWhenEmailIsBlank() {
    User user = mockUser("user-uuid-2", "  ", "+1234567890", null, "Alice", null, null);
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertEquals("+1234567890", captor.getValue().username());
  }

  @Test
  void publishSuccessShouldUsePreferredUsernameWhenEmailAndPhoneBlank() {
    User user = mockUser("user-uuid-3", null, null, "preferred_user", "Bob", null, null);
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertEquals("preferred_user", captor.getValue().username());
  }

  @Test
  void publishSuccessShouldUseNicknameForDisplayNameWhenNameIsBlank() {
    User user = mockUser("user-uuid-4", "u4@example.com", null, null, null, "Nickname4", null);
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertEquals("Nickname4", captor.getValue().displayName());
  }

  @Test
  void publishSuccessShouldUseGivenNameForDisplayNameWhenNameAndNicknameBlank() {
    User user = mockUser("user-uuid-5", "u5@example.com", null, null, null, null, "GivenName5");
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertEquals("GivenName5", captor.getValue().displayName());
  }

  @Test
  void publishSuccessShouldFallbackToIdWhenAllNameFieldsBlank() {
    User user = mockUser("user-uuid-6", null, null, null, null, null, null);
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertEquals("user-uuid-6", captor.getValue().username());
    assertEquals("user-uuid-6", captor.getValue().displayName());
  }

  @Test
  void publishSuccessShouldHandleNonUserPrincipal() {
    Authentication authentication = new UsernamePasswordAuthenticationToken("plain-username", null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationSuccessEvent event = captor.getValue();
    assertNull(event.userId());
    assertEquals("plain-username", event.username());
    assertEquals("UsernamePasswordAuthenticationToken", event.authenticationType());
  }

  @Test
  void publishSuccessShouldHandleNullAuthentication() {
    publisher.publishSuccess(request, null);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationSuccessEvent event = captor.getValue();
    assertNull(event.userId());
    assertNull(event.username());
    assertNull(event.authenticationType());
  }

  @Test
  void publishSuccessShouldExtractRequestHeaders() {
    request.addHeader("X-Tenant-Id", "tenant-abc");
    request.addHeader("X-Context-Id", "ctx-123");
    request.addHeader("X-Forwarded-For", "10.0.0.1");
    request.addHeader("User-Agent", "TestAgent/1.0");
    Authentication authentication = new UsernamePasswordAuthenticationToken("user", null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationSuccessEvent event = captor.getValue();
    assertEquals("tenant-abc", event.tenantId());
    assertEquals("ctx-123", event.contextId());
    assertEquals("10.0.0.1", event.forwardedFor());
    assertEquals("TestAgent/1.0", event.userAgent());
    assertEquals("127.0.0.1", event.remoteAddress());
    assertEquals("/oauth2/token", event.requestUri());
  }

  @Test
  void publishSuccessShouldIncludeSessionIdWhenSessionExists() {
    MockHttpSession session = new MockHttpSession(null, "session-id-xyz");
    request.setSession(session);
    Authentication authentication = new UsernamePasswordAuthenticationToken("user", null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertEquals("session-id-xyz", captor.getValue().sessionId());
  }

  @Test
  void publishSuccessShouldReturnNullSessionIdWhenNoSession() {
    Authentication authentication = new UsernamePasswordAuthenticationToken("user", null);

    publisher.publishSuccess(request, authentication);

    ArgumentCaptor<LoginAuthenticationSuccessEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationSuccessEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertNull(captor.getValue().sessionId());
  }

  // ── publishFailure(request, authentication, exception) ───────────────────

  @Test
  void publishFailureWithAuthenticationShouldPublishFailureEvent() {
    User user = mockUser("user-f1", "fail@example.com", null, null, "Fail User", null, null);
    Authentication authentication = new UsernamePasswordAuthenticationToken(user, null);
    AuthenticationException exception = new UsernameNotFoundException("User not found");

    publisher.publishFailure(request, authentication, exception);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationFailureEvent event = captor.getValue();
    assertEquals("user-f1", event.userId());
    assertEquals("fail@example.com", event.username());
    assertEquals("User not found", event.failureReason());
    assertNotNull(event.loginAt());
  }

  @Test
  void publishFailureWithNullAuthenticationShouldPublishFailureEvent() {
    AuthenticationException exception = new UsernameNotFoundException("Bad credentials");

    publisher.publishFailure(request, (Authentication) null, exception);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationFailureEvent event = captor.getValue();
    assertNull(event.userId());
    assertNull(event.authenticationType());
    assertEquals("Bad credentials", event.failureReason());
  }

  @Test
  void publishFailureWithNonUserPrincipalShouldPopulateUsernameFromAuthentication() {
    Authentication authentication = new UsernamePasswordAuthenticationToken("user@domain.com", null);
    AuthenticationException exception = new UsernameNotFoundException("Locked");

    publisher.publishFailure(request, authentication, exception);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationFailureEvent event = captor.getValue();
    assertEquals("user@domain.com", event.username());
    assertEquals("UsernamePasswordAuthenticationToken", event.authenticationType());
  }

  // ── publishFailure(request, username, exception) ─────────────────────────

  @Test
  void publishFailureWithUsernameShouldPublishFailureEvent() {
    AuthenticationException exception = new UsernameNotFoundException("Account locked");

    publisher.publishFailure(request, "john.doe", exception);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    LoginAuthenticationFailureEvent event = captor.getValue();
    assertNull(event.userId());
    assertEquals("john.doe", event.username());
    assertNull(event.authenticationType());
    assertEquals("Account locked", event.failureReason());
  }

  @Test
  void publishFailureWithBlankUsernameShouldNormalizeToNull() {
    AuthenticationException exception = new UsernameNotFoundException("Not found");

    publisher.publishFailure(request, "   ", exception);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertNull(captor.getValue().username());
  }

  @Test
  void publishFailureWithNullExceptionShouldPublishWithNullReason() {
    publisher.publishFailure(request, "user-x", null);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertNull(captor.getValue().failureReason());
  }

  @Test
  void publishFailureWithExceptionHavingEmptyMessageShouldUseClassSimpleName() {
    AuthenticationException exception = new UsernameNotFoundException("");

    publisher.publishFailure(request, "user-y", exception);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertEquals("UsernameNotFoundException", captor.getValue().failureReason());
  }

  @Test
  void publishFailureShouldNormalizeBlankHeadersToNull() {
    request.addHeader("X-Tenant-Id", "   ");
    request.addHeader("X-Forwarded-For", "");
    AuthenticationException exception = new UsernameNotFoundException("fail");

    publisher.publishFailure(request, "user-z", exception);

    ArgumentCaptor<LoginAuthenticationFailureEvent> captor =
        ArgumentCaptor.forClass(LoginAuthenticationFailureEvent.class);
    verify(applicationEventPublisher).publishEvent(captor.capture());

    assertNull(captor.getValue().tenantId());
    assertNull(captor.getValue().forwardedFor());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static User mockUser(
      final String id,
      final String email,
      final String phoneNumber,
      final String preferredUsername,
      final String name,
      final String nickname,
      final String givenName
  ) {
    User user = org.mockito.Mockito.mock(User.class);
    when(user.getId()).thenReturn(id);
    when(user.getEmail()).thenReturn(email);
    when(user.getPhoneNumber()).thenReturn(phoneNumber);
    when(user.getPreferredUsername()).thenReturn(preferredUsername);
    when(user.getName()).thenReturn(name);
    when(user.getNickname()).thenReturn(nickname);
    when(user.getGivenName()).thenReturn(givenName);
    when(user.getUsername()).thenReturn(id);
    return user;
  }
}
