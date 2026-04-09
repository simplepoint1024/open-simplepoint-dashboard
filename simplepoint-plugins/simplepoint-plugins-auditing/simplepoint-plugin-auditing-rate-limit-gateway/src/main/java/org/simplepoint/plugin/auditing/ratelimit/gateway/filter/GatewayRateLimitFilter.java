/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.filter;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.auditing.ratelimit.api.model.EndpointRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.model.RateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.model.ServiceRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitKeyResolver;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitRedisRuleProvider;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitRuleMatcher;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRedisRateLimiterSupport;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Applies service-level and endpoint-level rate limiting to gateway requests.
 */
@Slf4j
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

  private static final String SERVICE_RULE_PREFIX = "service-rule";
  private static final String ENDPOINT_RULE_PREFIX = "endpoint-rule";

  private final GatewayRateLimitRedisRuleProvider ruleProvider;
  private final GatewayRateLimitRuleMatcher ruleMatcher;
  private final GatewayRateLimitKeyResolver keyResolver;
  private final GatewayRedisRateLimiterSupport rateLimiterSupport;

  /**
   * Creates the gateway rate-limit filter.
   *
   * @param ruleProvider       the Redis rule provider
   * @param ruleMatcher        the rule matcher
   * @param keyResolver        the key resolver
   * @param rateLimiterSupport the Redis rate-limiter support
   */
  public GatewayRateLimitFilter(
      final GatewayRateLimitRedisRuleProvider ruleProvider,
      final GatewayRateLimitRuleMatcher ruleMatcher,
      final GatewayRateLimitKeyResolver keyResolver,
      final GatewayRedisRateLimiterSupport rateLimiterSupport
  ) {
    this.ruleProvider = ruleProvider;
    this.ruleMatcher = ruleMatcher;
    this.keyResolver = keyResolver;
    this.rateLimiterSupport = rateLimiterSupport;
  }

  /**
   * Applies the configured service-level and endpoint-level rate-limit checks.
   *
   * @param exchange the current exchange
   * @param chain    the gateway filter chain
   * @return the filter result
   */
  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
    String serviceId = ruleMatcher.resolveServiceId(exchange);
    if (serviceId == null) {
      return chain.filter(exchange);
    }
    return ruleProvider.getRules()
        .flatMap(rules -> applyServiceRule(serviceId, exchange, rules)
            .flatMap(serviceDecision -> {
              if (!serviceDecision.isAllowed()) {
                return reject(exchange, serviceDecision.getHeaders());
              }
              applyHeaders(exchange, serviceDecision.getHeaders());
              return applyEndpointRule(serviceId, exchange, rules)
                  .flatMap(endpointDecision -> {
                    if (!endpointDecision.isAllowed()) {
                      return reject(exchange, endpointDecision.getHeaders());
                    }
                    applyHeaders(exchange, endpointDecision.getHeaders());
                    return chain.filter(exchange);
                  });
            }))
        .onErrorResume(ex -> {
          log.error(
              "Failed to apply gateway rate limiting for request [{} {}]",
              exchange.getRequest().getMethod(),
              exchange.getRequest().getURI(),
              ex
          );
          exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
          return exchange.getResponse().setComplete();
        });
  }

  /**
   * Returns the gateway filter order.
   *
   * @return the filter order
   */
  @Override
  public int getOrder() {
    return -10;
  }

  private Mono<Response> applyServiceRule(
      final String serviceId,
      final ServerWebExchange exchange,
      final org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitRules rules
  ) {
    ServiceRateLimitRuleDefinition rule = ruleMatcher.findServiceRule(rules, serviceId);
    if (rule == null) {
      return Mono.just(new Response(true, Map.of()));
    }
    return applyRule(SERVICE_RULE_PREFIX, rule, exchange);
  }

  private Mono<Response> applyEndpointRule(
      final String serviceId,
      final ServerWebExchange exchange,
      final org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitRules rules
  ) {
    EndpointRateLimitRuleDefinition rule = ruleMatcher.findEndpointRule(rules, serviceId, exchange);
    if (rule == null) {
      return Mono.just(new Response(true, Map.of()));
    }
    return applyRule(ENDPOINT_RULE_PREFIX, rule, exchange);
  }

  private Mono<Response> applyRule(
      final String prefix,
      final RateLimitRuleDefinition rule,
      final ServerWebExchange exchange
  ) {
    return keyResolver.resolveKey(exchange, rule.getKeyStrategy())
        .flatMap(key -> rateLimiterSupport.isAllowed(
            prefix + ":" + rule.getId(),
            key,
            rule.getReplenishRate(),
            rule.getBurstCapacity(),
            rule.getRequestedTokens()
        ));
  }

  private Mono<Void> reject(final ServerWebExchange exchange, final Map<String, String> headers) {
    applyHeaders(exchange, headers);
    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    return exchange.getResponse().setComplete();
  }

  private void applyHeaders(final ServerWebExchange exchange, final Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return;
    }
    headers.forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));
  }
}
