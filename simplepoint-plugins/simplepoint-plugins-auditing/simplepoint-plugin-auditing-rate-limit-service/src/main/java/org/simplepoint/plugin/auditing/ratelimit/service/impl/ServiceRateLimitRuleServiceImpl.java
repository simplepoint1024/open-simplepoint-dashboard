/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.ratelimit.service.impl;

import java.util.Collection;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.auditing.ratelimit.api.entity.ServiceRateLimitRule;
import org.simplepoint.plugin.auditing.ratelimit.api.model.RateLimitKeyStrategy;
import org.simplepoint.plugin.auditing.ratelimit.api.repository.ServiceRateLimitRuleRepository;
import org.simplepoint.plugin.auditing.ratelimit.api.service.ServiceRateLimitRuleService;
import org.simplepoint.plugin.auditing.ratelimit.service.support.RateLimitRuleRedisPublisher;
import org.springframework.stereotype.Service;

/**
 * Service implementation for service-level rate-limit rules.
 */
@Service
public class ServiceRateLimitRuleServiceImpl
    extends BaseServiceImpl<ServiceRateLimitRuleRepository, ServiceRateLimitRule, String>
    implements ServiceRateLimitRuleService {

  private final RateLimitRuleRedisPublisher rateLimitRuleRedisPublisher;

  /**
   * Creates the service with the repository, details provider, and Redis publisher.
   *
   * @param repository                  the service-rule repository
   * @param detailsProviderService      the details provider service
   * @param rateLimitRuleRedisPublisher the Redis rule publisher
   */
  public ServiceRateLimitRuleServiceImpl(
      final ServiceRateLimitRuleRepository repository,
      final DetailsProviderService detailsProviderService,
      final RateLimitRuleRedisPublisher rateLimitRuleRedisPublisher
  ) {
    super(repository, detailsProviderService);
    this.rateLimitRuleRedisPublisher = rateLimitRuleRedisPublisher;
  }

  /**
   * Creates a service-level rule and republishes the active Redis snapshot.
   *
   * @param entity the entity to create
   * @param <S>    the entity type
   * @return the created entity
   */
  @Override
  public <S extends ServiceRateLimitRule> S create(final S entity) {
    normalizeAndValidate(entity);
    S created = super.create(entity);
    rateLimitRuleRedisPublisher.refreshServiceRules();
    return created;
  }

  /**
   * Creates multiple service-level rules and republishes the active Redis snapshot.
   *
   * @param entities the entities to create
   * @return the created entities
   */
  @Override
  public java.util.List<ServiceRateLimitRule> create(final Collection<ServiceRateLimitRule> entities) {
    if (entities != null) {
      entities.forEach(this::normalizeAndValidate);
    }
    java.util.List<ServiceRateLimitRule> created = super.create(entities);
    rateLimitRuleRedisPublisher.refreshServiceRules();
    return created;
  }

  /**
   * Updates a service-level rule and republishes the active Redis snapshot.
   *
   * @param entity the entity to update
   * @param <S>    the entity type
   * @return the updated entity
   */
  @Override
  public <S extends ServiceRateLimitRule> ServiceRateLimitRule modifyById(final S entity) {
    normalizeAndValidate(entity);
    ServiceRateLimitRule updated = super.modifyById(entity);
    rateLimitRuleRedisPublisher.refreshServiceRules();
    return updated;
  }

  /**
   * Removes a service-level rule by id and republishes the active Redis snapshot.
   *
   * @param id the rule identifier
   */
  @Override
  public void removeById(final String id) {
    super.removeById(id);
    rateLimitRuleRedisPublisher.refreshServiceRules();
  }

  /**
   * Removes service-level rules by ids and republishes the active Redis snapshot.
   *
   * @param ids the rule identifiers
   */
  @Override
  public void removeByIds(final Collection<String> ids) {
    super.removeByIds(ids);
    rateLimitRuleRedisPublisher.refreshServiceRules();
  }

  private void normalizeAndValidate(final ServiceRateLimitRule entity) {
    if (entity == null) {
      throw new NullPointerException("serviceRateLimitRule is null");
    }
    entity.setName(trimToNull(entity.getName()));
    entity.setServiceId(normalizeLower(entity.getServiceId()));
    entity.setKeyStrategy(RateLimitKeyStrategy.normalize(entity.getKeyStrategy()));
    entity.setDescription(trimToNull(entity.getDescription()));
    if (entity.getRequestedTokens() == null) {
      entity.setRequestedTokens(1);
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(Boolean.TRUE);
    }
    validateNumericValues(entity.getServiceId(), entity.getReplenishRate(), entity.getBurstCapacity(), entity.getRequestedTokens());
  }

  private void validateNumericValues(
      final String serviceId,
      final Integer replenishRate,
      final Long burstCapacity,
      final Integer requestedTokens
  ) {
    if (serviceId == null) {
      throw new IllegalArgumentException("serviceId must not be blank");
    }
    if (replenishRate == null || replenishRate <= 0) {
      throw new IllegalArgumentException("replenishRate must be greater than 0");
    }
    if (burstCapacity == null || burstCapacity <= 0) {
      throw new IllegalArgumentException("burstCapacity must be greater than 0");
    }
    if (burstCapacity < replenishRate) {
      throw new IllegalArgumentException("burstCapacity must be greater than or equal to replenishRate");
    }
    if (requestedTokens == null || requestedTokens <= 0) {
      throw new IllegalArgumentException("requestedTokens must be greater than 0");
    }
    if (requestedTokens > burstCapacity) {
      throw new IllegalArgumentException("requestedTokens must be less than or equal to burstCapacity");
    }
  }

  private String normalizeLower(final String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.toLowerCase();
  }

  private String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
