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
import org.simplepoint.plugin.auditing.ratelimit.api.entity.ServiceRateLimitRule;
import org.simplepoint.plugin.auditing.ratelimit.api.repository.ServiceRateLimitRuleRepository;
import org.simplepoint.plugin.auditing.ratelimit.service.support.RateLimitRuleRedisPublisher;

@ExtendWith(MockitoExtension.class)
class ServiceRateLimitRuleServiceImplTest {

  @Mock
  private ServiceRateLimitRuleRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private RateLimitRuleRedisPublisher rateLimitRuleRedisPublisher;

  @InjectMocks
  private ServiceRateLimitRuleServiceImpl service;

  // ── create validation ─────────────────────────────────────────────────────

  @Test
  void createShouldThrowWhenEntityIsNull() {
    assertThrows(NullPointerException.class, () -> service.create((ServiceRateLimitRule) null));
  }

  @Test
  void createShouldThrowWhenServiceIdIsBlank() {
    ServiceRateLimitRule rule = validRule();
    rule.setServiceId("  ");
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenReplenishRateIsNull() {
    ServiceRateLimitRule rule = validRule();
    rule.setReplenishRate(null);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenReplenishRateIsZero() {
    ServiceRateLimitRule rule = validRule();
    rule.setReplenishRate(0);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenBurstCapacityIsNull() {
    ServiceRateLimitRule rule = validRule();
    rule.setBurstCapacity(null);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenBurstCapacityIsLessThanReplenishRate() {
    ServiceRateLimitRule rule = validRule();
    rule.setReplenishRate(50);
    rule.setBurstCapacity(30L);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldThrowWhenRequestedTokensExceedBurstCapacity() {
    ServiceRateLimitRule rule = validRule();
    rule.setBurstCapacity(5L);
    rule.setReplenishRate(5);
    rule.setRequestedTokens(10);
    assertThrows(IllegalArgumentException.class, () -> service.create(rule));
  }

  @Test
  void createShouldNormalizeShouldLowerCaseServiceId() {
    ServiceRateLimitRule rule = validRule();
    rule.setServiceId("  AuthService  ");
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ServiceRateLimitRule saved = service.create(rule);

    assertEquals("authservice", saved.getServiceId());
    verify(rateLimitRuleRedisPublisher).refreshServiceRules();
  }

  @Test
  void createShouldPublishToRedisOnSuccess() {
    ServiceRateLimitRule rule = validRule();
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(rule);

    verify(rateLimitRuleRedisPublisher).refreshServiceRules();
  }

  @Test
  void createShouldDefaultRequestedTokensToOneWhenNull() {
    ServiceRateLimitRule rule = validRule();
    rule.setRequestedTokens(null);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ServiceRateLimitRule saved = service.create(rule);
    assertEquals(1, saved.getRequestedTokens());
  }

  @Test
  void createShouldDefaultEnabledToTrueWhenNull() {
    ServiceRateLimitRule rule = validRule();
    rule.setEnabled(null);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ServiceRateLimitRule saved = service.create(rule);
    assertEquals(Boolean.TRUE, saved.getEnabled());
  }

  // ── removeById ────────────────────────────────────────────────────────────

  @Test
  void removeByIdShouldPublishToRedis() {
    service.removeById("rule-1");
    verify(rateLimitRuleRedisPublisher).refreshServiceRules();
  }

  // ── removeByIds ───────────────────────────────────────────────────────────

  @Test
  void removeByIdsShouldPublishToRedis() {
    service.removeByIds(List.of("r1", "r2"));
    verify(rateLimitRuleRedisPublisher).refreshServiceRules();
  }

  // ── create(Collection) ────────────────────────────────────────────────────

  @Test
  void createBatchShouldPublishToRedis() {
    ServiceRateLimitRule rule = validRule();
    when(repository.saveAll(any())).thenReturn(java.util.List.of(rule));

    service.create(List.of(rule));

    verify(rateLimitRuleRedisPublisher).refreshServiceRules();
  }

  @Test
  void createBatchWithNullListShouldStillPublish() {
    service.create((java.util.Collection<ServiceRateLimitRule>) null);
    verify(rateLimitRuleRedisPublisher).refreshServiceRules();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static ServiceRateLimitRule validRule() {
    ServiceRateLimitRule rule = new ServiceRateLimitRule();
    rule.setName("Test Rule");
    rule.setServiceId("auth-service");
    rule.setReplenishRate(10);
    rule.setBurstCapacity(20L);
    rule.setRequestedTokens(1);
    rule.setEnabled(Boolean.TRUE);
    rule.setKeyStrategy("CLIENT_IP");
    return rule;
  }
}
