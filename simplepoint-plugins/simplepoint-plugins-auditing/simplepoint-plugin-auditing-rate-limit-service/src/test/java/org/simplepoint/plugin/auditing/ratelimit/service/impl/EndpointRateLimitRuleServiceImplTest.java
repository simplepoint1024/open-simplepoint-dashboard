/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.auditing.ratelimit.api.entity.EndpointRateLimitRule;
import org.simplepoint.plugin.auditing.ratelimit.api.repository.EndpointRateLimitRuleRepository;
import org.simplepoint.plugin.auditing.ratelimit.service.support.RateLimitRuleRedisPublisher;

@ExtendWith(MockitoExtension.class)
class EndpointRateLimitRuleServiceImplTest {

  @Mock
  private EndpointRateLimitRuleRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private RateLimitRuleRedisPublisher rateLimitRuleRedisPublisher;

  @InjectMocks
  private EndpointRateLimitRuleServiceImpl service;

  // ── create validation ─────────────────────────────────────────────────────

  @Test
  void createShouldThrowWhenEntityIsNull() {
    assertThrows(NullPointerException.class, () -> service.create((EndpointRateLimitRule) null));
  }

  @Test
  void createShouldThrowWhenServiceIdIsBlank() {
    EndpointRateLimitRule rule = validRule();
    rule.setServiceId("  ");
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenPathPatternIsBlank() {
    EndpointRateLimitRule rule = validRule();
    rule.setPathPattern("  ");
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenReplenishRateIsZero() {
    EndpointRateLimitRule rule = validRule();
    rule.setReplenishRate(0);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenBurstCapacityLessThanReplenishRate() {
    EndpointRateLimitRule rule = validRule();
    rule.setReplenishRate(50);
    rule.setBurstCapacity(10L);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenRequestedTokensExceedBurstCapacity() {
    EndpointRateLimitRule rule = validRule();
    rule.setRequestedTokens(100);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldNormalizeLowerCaseServiceIdAndUpperCaseHttpMethod() {
    EndpointRateLimitRule rule = validRule();
    rule.setServiceId("  AuthService  ");
    rule.setHttpMethod("  get  ");
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    EndpointRateLimitRule saved = service.create(rule);

    assertEquals("authservice", saved.getServiceId());
    assertEquals("GET", saved.getHttpMethod());
    verify(rateLimitRuleRedisPublisher).refreshEndpointRules();
  }

  @Test
  void createShouldDefaultSortToZeroWhenNull() {
    EndpointRateLimitRule rule = validRule();
    rule.setSort(null);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    EndpointRateLimitRule saved = service.create(rule);
    assertEquals(0, saved.getSort());
  }

  @Test
  void createShouldDefaultEnabledToTrueWhenNull() {
    EndpointRateLimitRule rule = validRule();
    rule.setEnabled(null);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    EndpointRateLimitRule saved = service.create(rule);
    assertEquals(Boolean.TRUE, saved.getEnabled());
  }

  @Test
  void createShouldPublishToRedisOnSuccess() {
    EndpointRateLimitRule rule = validRule();
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(rule);

    verify(rateLimitRuleRedisPublisher).refreshEndpointRules();
  }

  // ── removeById ────────────────────────────────────────────────────────────

  @Test
  void removeByIdShouldPublishToRedis() {
    service.removeById("rule-1");
    verify(rateLimitRuleRedisPublisher).refreshEndpointRules();
  }

  // ── removeByIds ───────────────────────────────────────────────────────────

  @Test
  void removeByIdsShouldPublishToRedis() {
    service.removeByIds(List.of("r1", "r2"));
    verify(rateLimitRuleRedisPublisher).refreshEndpointRules();
  }

  // ── create(Collection) ────────────────────────────────────────────────────

  @Test
  void createBatchShouldPublishToRedis() {
    EndpointRateLimitRule rule = validRule();
    when(repository.saveAll(any())).thenReturn(java.util.List.of(rule));

    service.create(List.of(rule));

    verify(rateLimitRuleRedisPublisher).refreshEndpointRules();
  }

  @Test
  void createBatchWithNullListShouldStillPublish() {
    service.create((java.util.Collection<EndpointRateLimitRule>) null);
    verify(rateLimitRuleRedisPublisher).refreshEndpointRules();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static EndpointRateLimitRule validRule() {
    EndpointRateLimitRule rule = new EndpointRateLimitRule();
    rule.setName("Test Endpoint Rule");
    rule.setServiceId("auth-service");
    rule.setPathPattern("/api/**");
    rule.setHttpMethod("POST");
    rule.setSort(0);
    rule.setReplenishRate(10);
    rule.setBurstCapacity(20L);
    rule.setRequestedTokens(1);
    rule.setEnabled(Boolean.TRUE);
    rule.setKeyStrategy("CLIENT_IP");
    return rule;
  }
}
