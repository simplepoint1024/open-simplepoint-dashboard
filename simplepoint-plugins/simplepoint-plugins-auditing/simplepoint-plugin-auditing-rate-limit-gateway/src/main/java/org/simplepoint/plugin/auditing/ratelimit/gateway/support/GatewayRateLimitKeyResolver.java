/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.support;

import java.net.InetSocketAddress;
import java.security.Principal;
import org.simplepoint.plugin.auditing.ratelimit.api.model.RateLimitKeyStrategy;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Resolves the effective rate-limit key for a gateway request.
 */
public class GatewayRateLimitKeyResolver {

  /**
   * Resolves the rate-limit key for the provided strategy.
   *
   * @param exchange     the current exchange
   * @param keyStrategy  the key strategy
   * @return the resolved key
   */
  public Mono<String> resolveKey(final ServerWebExchange exchange, final String keyStrategy) {
    RateLimitKeyStrategy strategy = RateLimitKeyStrategy.valueOf(RateLimitKeyStrategy.normalize(keyStrategy));
    return switch (strategy) {
      case GLOBAL -> Mono.just("global");
      case CLIENT_IP -> Mono.just(resolveClientIp(exchange));
      case TENANT_ID -> Mono.just(firstNonBlank(
          exchange.getRequest().getHeaders().getFirst("X-Tenant-Id"),
          "default"
      ));
      case USER_ID -> exchange.getPrincipal()
          .map(Principal::getName)
          .map(this::trimToNull)
          .defaultIfEmpty("anonymous")
          .map(value -> value == null ? "anonymous" : value);
    };
  }

  private String resolveClientIp(final ServerWebExchange exchange) {
    String forwardedFor = trimToNull(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));
    if (forwardedFor != null) {
      String[] candidates = forwardedFor.split(",");
      if (candidates.length > 0) {
        String firstCandidate = trimToNull(candidates[0]);
        if (firstCandidate != null) {
          return firstCandidate;
        }
      }
    }
    InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
    if (remoteAddress == null || remoteAddress.getAddress() == null) {
      return "unknown";
    }
    String hostAddress = trimToNull(remoteAddress.getAddress().getHostAddress());
    return hostAddress == null ? "unknown" : hostAddress;
  }

  private String firstNonBlank(final String value, final String fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
