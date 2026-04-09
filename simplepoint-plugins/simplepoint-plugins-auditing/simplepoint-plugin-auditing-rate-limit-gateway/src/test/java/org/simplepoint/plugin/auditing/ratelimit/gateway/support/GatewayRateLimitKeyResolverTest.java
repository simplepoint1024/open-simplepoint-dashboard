/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayRateLimitKeyResolverTest {

  @Test
  void shouldResolveClientIpFromForwardedForHeader() {
    GatewayRateLimitKeyResolver resolver = new GatewayRateLimitKeyResolver();
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/authorization/oauth2/token")
            .header("X-Forwarded-For", "10.10.10.1, 10.10.10.2")
            .build()
    );

    String key = resolver.resolveKey(exchange, "CLIENT_IP").block();

    assertEquals("10.10.10.1", key);
  }

  @Test
  void shouldResolveTenantIdWithDefaultFallback() {
    GatewayRateLimitKeyResolver resolver = new GatewayRateLimitKeyResolver();
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/common/users").build());

    String key = resolver.resolveKey(exchange, "TENANT_ID").block();

    assertEquals("default", key);
  }
}
