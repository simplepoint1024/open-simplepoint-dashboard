/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.auditing.ratelimit.api.model.EndpointRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.model.ServiceRateLimitRuleDefinition;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayRateLimitRuleMatcherTest {

  @Test
  void shouldFindMatchingServiceRule() {
    GatewayRateLimitRuleMatcher matcher = new GatewayRateLimitRuleMatcher();
    GatewayRateLimitRules rules = new GatewayRateLimitRules(
        List.of(new ServiceRateLimitRuleDefinition("svc-1", "Authorization", "authorization", "CLIENT_IP", 10, 20L, 1, true, null)),
        List.of()
    );

    ServiceRateLimitRuleDefinition rule = matcher.findServiceRule(rules, "authorization");

    assertNotNull(rule);
    assertEquals("svc-1", rule.getId());
  }

  @Test
  void shouldPickMostSpecificEndpointRule() {
    GatewayRateLimitRuleMatcher matcher = new GatewayRateLimitRuleMatcher();
    GatewayRateLimitRules rules = new GatewayRateLimitRules(
        List.of(),
        List.of(
            new EndpointRateLimitRuleDefinition(
                "ep-1", "Generic Authorization", "authorization", "/authorization/**", "POST", 0,
                "CLIENT_IP", 10, 20L, 1, true, null
            ),
            new EndpointRateLimitRuleDefinition(
                "ep-2", "Token Endpoint", "authorization", "/authorization/oauth2/token", "POST", 0,
                "CLIENT_IP", 5, 10L, 1, true, null
            )
        )
    );
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.method(HttpMethod.POST, "/authorization/oauth2/token").build()
    );

    EndpointRateLimitRuleDefinition rule = matcher.findEndpointRule(rules, "authorization", exchange);

    assertNotNull(rule);
    assertEquals("ep-2", rule.getId());
  }

  @Test
  void shouldIgnoreMethodMismatch() {
    GatewayRateLimitRuleMatcher matcher = new GatewayRateLimitRuleMatcher();
    GatewayRateLimitRules rules = new GatewayRateLimitRules(
        List.of(),
        List.of(
            new EndpointRateLimitRuleDefinition(
                "ep-1", "Post Only", "authorization", "/authorization/oauth2/token", "POST", 0,
                "CLIENT_IP", 10, 20L, 1, true, null
            )
        )
    );
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.method(HttpMethod.GET, "/authorization/oauth2/token").build()
    );

    EndpointRateLimitRuleDefinition rule = matcher.findEndpointRule(rules, "authorization", exchange);

    assertNull(rule);
  }
}
