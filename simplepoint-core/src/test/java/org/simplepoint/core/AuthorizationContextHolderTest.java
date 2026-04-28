/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuthorizationContextHolderTest {

  @Test
  void getContext_returnsNull_whenNoRequestContext() {
    RequestContextHolder.resetRequestAttributes();
    AuthorizationContext ctx = AuthorizationContextHolder.getContext();
    assertThat(ctx).isNull();
  }

  @Test
  void getContext_returnsStoredContext_whenPresentInRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    AuthorizationContext stored = new AuthorizationContext();
    stored.setAttributes(Map.of("X-Tenant-Id", "t1"));
    request.setAttribute(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, stored);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    try {
      AuthorizationContext result = AuthorizationContextHolder.getContext();
      assertThat(result).isEqualTo(stored);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }
}
