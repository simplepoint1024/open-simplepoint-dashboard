/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.support;

import java.net.URI;
import java.util.Comparator;
import org.simplepoint.plugin.auditing.ratelimit.api.model.EndpointRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.model.ServiceRateLimitRuleDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

/**
 * Matches incoming gateway exchanges against service-level and endpoint-level rate-limit rules.
 */
public class GatewayRateLimitRuleMatcher {

  private final AntPathMatcher antPathMatcher = new AntPathMatcher();

  /**
   * Resolves the downstream service identifier for the current exchange.
   *
   * @param exchange the current exchange
   * @return the downstream service identifier, or {@code null} when unavailable
   */
  public String resolveServiceId(final ServerWebExchange exchange) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route == null) {
      return null;
    }
    URI uri = route.getUri();
    String host = uri == null ? null : trimToNull(uri.getHost());
    if (host != null) {
      return host.toLowerCase();
    }
    String routeId = trimToNull(route.getId());
    return routeId == null ? null : routeId.toLowerCase();
  }

  /**
   * Finds the matching service-level rule for the resolved service id.
   *
   * @param rules     the current rule snapshot
   * @param serviceId the resolved service id
   * @return the matching rule, or {@code null} if none matches
   */
  public ServiceRateLimitRuleDefinition findServiceRule(final GatewayRateLimitRules rules, final String serviceId) {
    if (rules == null || serviceId == null) {
      return null;
    }
    return rules.serviceRules().stream()
        .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
        .filter(rule -> serviceId.equalsIgnoreCase(trimToNull(rule.getServiceId())))
        .findFirst()
        .orElse(null);
  }

  /**
   * Finds the best matching endpoint-level rule for the current exchange.
   *
   * @param rules     the current rule snapshot
   * @param serviceId the resolved service id
   * @param exchange  the current exchange
   * @return the best matching rule, or {@code null} if none matches
   */
  public EndpointRateLimitRuleDefinition findEndpointRule(
      final GatewayRateLimitRules rules,
      final String serviceId,
      final ServerWebExchange exchange
  ) {
    if (rules == null || serviceId == null || exchange == null) {
      return null;
    }
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    String method = exchange.getRequest().getMethod() == null ? null : exchange.getRequest().getMethod().name();
    return rules.endpointRules().stream()
        .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
        .filter(rule -> serviceId.equalsIgnoreCase(trimToNull(rule.getServiceId())))
        .filter(rule -> matchesMethod(rule, method))
        .filter(rule -> matchesPath(rule, path))
        .sorted(Comparator
            .comparing(EndpointRateLimitRuleDefinition::getSort, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(rule -> pathSpecificity(rule.getPathPattern()), Comparator.reverseOrder()))
        .findFirst()
        .orElse(null);
  }

  private boolean matchesMethod(final EndpointRateLimitRuleDefinition rule, final String method) {
    String configuredMethod = trimToNull(rule.getHttpMethod());
    return configuredMethod == null || configuredMethod.equalsIgnoreCase(method);
  }

  private boolean matchesPath(final EndpointRateLimitRuleDefinition rule, final String path) {
    String configuredPath = trimToNull(rule.getPathPattern());
    return configuredPath != null && antPathMatcher.match(configuredPath, path);
  }

  private int pathSpecificity(final String value) {
    String normalized = trimToNull(value);
    return normalized == null ? -1 : normalized.length();
  }

  private String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
