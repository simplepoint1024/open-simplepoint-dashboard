/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.auditing.ratelimit.api.model.EndpointRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.model.ServiceRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.gateway.properties.GatewayRateLimitProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Loads and caches effective gateway rate-limit rules from Redis.
 */
public class GatewayRateLimitRedisRuleProvider {

  private static final TypeReference<List<ServiceRateLimitRuleDefinition>> SERVICE_RULE_LIST_TYPE = new TypeReference<>() {
  };
  private static final TypeReference<List<EndpointRateLimitRuleDefinition>> ENDPOINT_RULE_LIST_TYPE = new TypeReference<>() {
  };

  private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;
  private final ObjectMapper objectMapper;
  private final GatewayRateLimitProperties properties;

  private volatile CachedRules cachedRules;

  /**
   * Creates the Redis-backed rule provider.
   *
   * @param reactiveStringRedisTemplate the reactive Redis template
   * @param objectMapper                the object mapper
   * @param properties                  the rate-limit properties
   */
  public GatewayRateLimitRedisRuleProvider(
      final ReactiveStringRedisTemplate reactiveStringRedisTemplate,
      final ObjectMapper objectMapper,
      final GatewayRateLimitProperties properties
  ) {
    this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  /**
   * Returns the current rule snapshot, loading from Redis when the local cache expires.
   *
   * @return the current rule snapshot
   */
  public Mono<GatewayRateLimitRules> getRules() {
    CachedRules current = cachedRules;
    if (current != null && !current.isExpired()) {
      return Mono.just(current.rules());
    }
    return Mono.zip(loadServiceRules(), loadEndpointRules())
        .map(tuple -> new GatewayRateLimitRules(tuple.getT1(), tuple.getT2()))
        .doOnNext(this::cacheRules);
  }

  private Mono<List<ServiceRateLimitRuleDefinition>> loadServiceRules() {
    return reactiveStringRedisTemplate.opsForValue()
        .get(properties.getServiceRulesKey())
        .flatMap(json -> deserializeList(json, SERVICE_RULE_LIST_TYPE))
        .defaultIfEmpty(List.of());
  }

  private Mono<List<EndpointRateLimitRuleDefinition>> loadEndpointRules() {
    return reactiveStringRedisTemplate.opsForValue()
        .get(properties.getEndpointRulesKey())
        .flatMap(json -> deserializeList(json, ENDPOINT_RULE_LIST_TYPE))
        .defaultIfEmpty(List.of());
  }

  private <T> Mono<List<T>> deserializeList(final String json, final TypeReference<List<T>> typeReference) {
    if (json == null || json.isBlank()) {
      return Mono.just(List.of());
    }
    return Mono.fromCallable(() -> {
      List<T> rules = objectMapper.readValue(json, typeReference);
      return rules == null ? List.of() : List.copyOf(rules);
    });
  }

  private void cacheRules(final GatewayRateLimitRules rules) {
    long cacheSeconds = properties.getCacheSeconds() <= 0 ? 1 : properties.getCacheSeconds();
    this.cachedRules = new CachedRules(rules == null ? GatewayRateLimitRules.empty() : rules,
        Instant.now().plusSeconds(cacheSeconds));
  }

  private record CachedRules(GatewayRateLimitRules rules, Instant expiresAt) {

    /**
     * Returns whether the cached snapshot has expired.
     *
     * @return whether the cache entry has expired
     */
    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }
}
