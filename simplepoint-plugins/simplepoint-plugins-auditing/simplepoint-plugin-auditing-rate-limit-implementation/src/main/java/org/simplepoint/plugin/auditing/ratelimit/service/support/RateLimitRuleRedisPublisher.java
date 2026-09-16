/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.simplepoint.plugin.auditing.ratelimit.api.entity.EndpointRateLimitRule;
import org.simplepoint.plugin.auditing.ratelimit.api.entity.ServiceRateLimitRule;
import org.simplepoint.plugin.auditing.ratelimit.api.model.EndpointRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.model.RateLimitKeyStrategy;
import org.simplepoint.plugin.auditing.ratelimit.api.model.RateLimitRedisKeys;
import org.simplepoint.plugin.auditing.ratelimit.api.model.ServiceRateLimitRuleDefinition;
import org.simplepoint.plugin.auditing.ratelimit.api.repository.EndpointRateLimitRuleRepository;
import org.simplepoint.plugin.auditing.ratelimit.api.repository.ServiceRateLimitRuleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes effective gateway rate-limit rules into Redis for the host gateway to consume.
 */
@Component
public class RateLimitRuleRedisPublisher implements ApplicationRunner {

  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper;
  private final ServiceRateLimitRuleRepository serviceRateLimitRuleRepository;
  private final EndpointRateLimitRuleRepository endpointRateLimitRuleRepository;

  /**
   * Creates the Redis publisher used to publish effective rule snapshots.
   *
   * @param stringRedisTemplate             the Redis template
   * @param objectMapper                    the object mapper
   * @param serviceRateLimitRuleRepository  the service-rule repository
   * @param endpointRateLimitRuleRepository the endpoint-rule repository
   */
  public RateLimitRuleRedisPublisher(
      final StringRedisTemplate stringRedisTemplate,
      final ObjectMapper objectMapper,
      final ServiceRateLimitRuleRepository serviceRateLimitRuleRepository,
      final EndpointRateLimitRuleRepository endpointRateLimitRuleRepository
  ) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.objectMapper = objectMapper;
    this.serviceRateLimitRuleRepository = serviceRateLimitRuleRepository;
    this.endpointRateLimitRuleRepository = endpointRateLimitRuleRepository;
  }

  /**
   * Publishes all current rate-limit rules at application startup.
   *
   * @param args application arguments
   */
  @Override
  public void run(final ApplicationArguments args) {
    refreshAll();
  }

  /**
   * Refreshes both service-level and endpoint-level rule snapshots in Redis.
   */
  public void refreshAll() {
    refreshServiceRules();
    refreshEndpointRules();
  }

  /**
   * Refreshes the service-level rule snapshot in Redis.
   */
  public void refreshServiceRules() {
    List<ServiceRateLimitRuleDefinition> rules = serviceRateLimitRuleRepository.findAll(Map.of()).stream()
        .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
        .sorted(Comparator.comparing(ServiceRateLimitRule::getServiceId, Comparator.nullsLast(String::compareToIgnoreCase)))
        .map(this::toServiceDefinition)
        .collect(Collectors.toList());
    writeSnapshot(RateLimitRedisKeys.SERVICE_RULES_KEY, rules);
  }

  /**
   * Refreshes the endpoint-level rule snapshot in Redis.
   */
  public void refreshEndpointRules() {
    List<EndpointRateLimitRuleDefinition> rules = endpointRateLimitRuleRepository.findAll(Map.of()).stream()
        .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
        .sorted(Comparator
            .comparing(EndpointRateLimitRule::getSort, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(EndpointRateLimitRule::getServiceId, Comparator.nullsLast(String::compareToIgnoreCase))
            .thenComparing(EndpointRateLimitRule::getPathPattern, Comparator.nullsLast(String::compareToIgnoreCase)))
        .map(this::toEndpointDefinition)
        .collect(Collectors.toList());
    writeSnapshot(RateLimitRedisKeys.ENDPOINT_RULES_KEY, rules);
  }

  private ServiceRateLimitRuleDefinition toServiceDefinition(final ServiceRateLimitRule rule) {
    return new ServiceRateLimitRuleDefinition(
        rule.getId(),
        rule.getName(),
        rule.getServiceId(),
        RateLimitKeyStrategy.normalize(rule.getKeyStrategy()),
        rule.getReplenishRate(),
        rule.getBurstCapacity(),
        rule.getRequestedTokens(),
        rule.getEnabled(),
        rule.getDescription()
    );
  }

  private EndpointRateLimitRuleDefinition toEndpointDefinition(final EndpointRateLimitRule rule) {
    return new EndpointRateLimitRuleDefinition(
        rule.getId(),
        rule.getName(),
        rule.getServiceId(),
        rule.getPathPattern(),
        rule.getHttpMethod(),
        rule.getSort(),
        RateLimitKeyStrategy.normalize(rule.getKeyStrategy()),
        rule.getReplenishRate(),
        rule.getBurstCapacity(),
        rule.getRequestedTokens(),
        rule.getEnabled(),
        rule.getDescription()
    );
  }

  private void writeSnapshot(final String key, final Object value) {
    try {
      stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to publish rate-limit rules to redis key: " + key, ex);
    }
  }
}
