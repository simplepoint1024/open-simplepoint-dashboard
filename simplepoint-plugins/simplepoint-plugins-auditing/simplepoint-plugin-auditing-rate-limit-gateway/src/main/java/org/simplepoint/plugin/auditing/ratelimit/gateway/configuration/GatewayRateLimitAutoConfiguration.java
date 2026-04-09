/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.gateway.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.simplepoint.plugin.auditing.ratelimit.gateway.filter.GatewayRateLimitFilter;
import org.simplepoint.plugin.auditing.ratelimit.gateway.properties.GatewayRateLimitProperties;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitKeyResolver;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitRedisRuleProvider;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRateLimitRuleMatcher;
import org.simplepoint.plugin.auditing.ratelimit.gateway.support.GatewayRedisRateLimiterSupport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Auto-configuration for gateway service-level and endpoint-level rate limiting.
 */
@AutoConfiguration
@EnableConfigurationProperties(GatewayRateLimitProperties.class)
@ConditionalOnClass({GlobalFilter.class, ReactiveStringRedisTemplate.class})
@ConditionalOnProperty(prefix = GatewayRateLimitProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class GatewayRateLimitAutoConfiguration {

  /**
   * Creates the Redis-backed rule provider.
   *
   * @param reactiveStringRedisTemplate the reactive Redis template
   * @param objectMapper                the object mapper
   * @param properties                  the rate-limit properties
   * @return the rule provider
   */
  @Bean
  @ConditionalOnMissingBean
  public GatewayRateLimitRedisRuleProvider gatewayRateLimitRedisRuleProvider(
      final ReactiveStringRedisTemplate reactiveStringRedisTemplate,
      final ObjectMapper objectMapper,
      final GatewayRateLimitProperties properties
  ) {
    return new GatewayRateLimitRedisRuleProvider(reactiveStringRedisTemplate, objectMapper, properties);
  }

  /**
   * Creates the gateway rule matcher.
   *
   * @return the gateway rule matcher
   */
  @Bean
  @ConditionalOnMissingBean
  public GatewayRateLimitRuleMatcher gatewayRateLimitRuleMatcher() {
    return new GatewayRateLimitRuleMatcher();
  }

  /**
   * Creates the gateway key resolver.
   *
   * @return the gateway key resolver
   */
  @Bean
  @ConditionalOnMissingBean
  public GatewayRateLimitKeyResolver gatewayRateLimitKeyResolver() {
    return new GatewayRateLimitKeyResolver();
  }

  /**
   * Creates the RedisRateLimiter support wrapper.
   *
   * @param applicationContext the application context
   * @return the limiter support wrapper
   */
  @Bean
  @ConditionalOnMissingBean
  public GatewayRedisRateLimiterSupport gatewayRedisRateLimiterSupport(final ApplicationContext applicationContext) {
    return new GatewayRedisRateLimiterSupport(applicationContext);
  }

  /**
   * Creates the global gateway rate-limit filter.
   *
   * @param ruleProvider       the rule provider
   * @param ruleMatcher        the rule matcher
   * @param keyResolver        the key resolver
   * @param rateLimiterSupport the limiter support wrapper
   * @return the global gateway rate-limit filter
   */
  @Bean
  @ConditionalOnMissingBean
  public GatewayRateLimitFilter gatewayRateLimitFilter(
      final GatewayRateLimitRedisRuleProvider ruleProvider,
      final GatewayRateLimitRuleMatcher ruleMatcher,
      final GatewayRateLimitKeyResolver keyResolver,
      final GatewayRedisRateLimiterSupport rateLimiterSupport
  ) {
    return new GatewayRateLimitFilter(ruleProvider, ruleMatcher, keyResolver, rateLimiterSupport);
  }
}
