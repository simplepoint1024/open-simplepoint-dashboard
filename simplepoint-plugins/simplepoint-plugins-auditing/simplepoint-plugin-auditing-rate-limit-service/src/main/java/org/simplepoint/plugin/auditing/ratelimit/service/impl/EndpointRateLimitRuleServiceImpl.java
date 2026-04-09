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
import org.simplepoint.plugin.auditing.ratelimit.api.entity.EndpointRateLimitRule;
import org.simplepoint.plugin.auditing.ratelimit.api.model.RateLimitKeyStrategy;
import org.simplepoint.plugin.auditing.ratelimit.api.repository.EndpointRateLimitRuleRepository;
import org.simplepoint.plugin.auditing.ratelimit.api.service.EndpointRateLimitRuleService;
import org.simplepoint.plugin.auditing.ratelimit.service.support.RateLimitRuleRedisPublisher;
import org.springframework.stereotype.Service;

/**
 * Service implementation for endpoint-level rate-limit rules.
 */
@Service
public class EndpointRateLimitRuleServiceImpl
    extends BaseServiceImpl<EndpointRateLimitRuleRepository, EndpointRateLimitRule, String>
    implements EndpointRateLimitRuleService {

  private final RateLimitRuleRedisPublisher rateLimitRuleRedisPublisher;

  /**
   * Creates the service with the repository, details provider, and Redis publisher.
   *
   * @param repository                  the endpoint-rule repository
   * @param detailsProviderService      the details provider service
   * @param rateLimitRuleRedisPublisher the Redis rule publisher
   */
  public EndpointRateLimitRuleServiceImpl(
      final EndpointRateLimitRuleRepository repository,
      final DetailsProviderService detailsProviderService,
      final RateLimitRuleRedisPublisher rateLimitRuleRedisPublisher
  ) {
    super(repository, detailsProviderService);
    this.rateLimitRuleRedisPublisher = rateLimitRuleRedisPublisher;
  }

  /**
   * Creates an endpoint-level rule and republishes the active Redis snapshot.
   *
   * @param entity the entity to create
   * @param <S>    the entity type
   * @return the created entity
   */
  @Override
  public <S extends EndpointRateLimitRule> S create(final S entity) {
    normalizeAndValidate(entity);
    S created = super.create(entity);
    rateLimitRuleRedisPublisher.refreshEndpointRules();
    return created;
  }

  /**
   * Creates multiple endpoint-level rules and republishes the active Redis snapshot.
   *
   * @param entities the entities to create
   * @return the created entities
   */
  @Override
  public java.util.List<EndpointRateLimitRule> create(final Collection<EndpointRateLimitRule> entities) {
    if (entities != null) {
      entities.forEach(this::normalizeAndValidate);
    }
    java.util.List<EndpointRateLimitRule> created = super.create(entities);
    rateLimitRuleRedisPublisher.refreshEndpointRules();
    return created;
  }

  /**
   * Updates an endpoint-level rule and republishes the active Redis snapshot.
   *
   * @param entity the entity to update
   * @param <S>    the entity type
   * @return the updated entity
   */
  @Override
  public <S extends EndpointRateLimitRule> EndpointRateLimitRule modifyById(final S entity) {
    normalizeAndValidate(entity);
    EndpointRateLimitRule updated = super.modifyById(entity);
    rateLimitRuleRedisPublisher.refreshEndpointRules();
    return updated;
  }

  /**
   * Removes an endpoint-level rule by id and republishes the active Redis snapshot.
   *
   * @param id the rule identifier
   */
  @Override
  public void removeById(final String id) {
    super.removeById(id);
    rateLimitRuleRedisPublisher.refreshEndpointRules();
  }

  /**
   * Removes endpoint-level rules by ids and republishes the active Redis snapshot.
   *
   * @param ids the rule identifiers
   */
  @Override
  public void removeByIds(final Collection<String> ids) {
    super.removeByIds(ids);
    rateLimitRuleRedisPublisher.refreshEndpointRules();
  }

  private void normalizeAndValidate(final EndpointRateLimitRule entity) {
    if (entity == null) {
      throw new NullPointerException("endpointRateLimitRule is null");
    }
    entity.setName(trimToNull(entity.getName()));
    entity.setServiceId(normalizeLower(entity.getServiceId()));
    entity.setPathPattern(trimToNull(entity.getPathPattern()));
    entity.setHttpMethod(normalizeUpper(entity.getHttpMethod()));
    entity.setSort(entity.getSort() == null ? 0 : entity.getSort());
    entity.setKeyStrategy(RateLimitKeyStrategy.normalize(entity.getKeyStrategy()));
    entity.setDescription(trimToNull(entity.getDescription()));
    if (entity.getRequestedTokens() == null) {
      entity.setRequestedTokens(1);
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(Boolean.TRUE);
    }
    validateNumericValues(entity);
  }

  private void validateNumericValues(final EndpointRateLimitRule entity) {
    if (entity.getServiceId() == null) {
      throw new IllegalArgumentException("serviceId must not be blank");
    }
    if (entity.getPathPattern() == null) {
      throw new IllegalArgumentException("pathPattern must not be blank");
    }
    if (entity.getReplenishRate() == null || entity.getReplenishRate() <= 0) {
      throw new IllegalArgumentException("replenishRate must be greater than 0");
    }
    if (entity.getBurstCapacity() == null || entity.getBurstCapacity() <= 0) {
      throw new IllegalArgumentException("burstCapacity must be greater than 0");
    }
    if (entity.getBurstCapacity() < entity.getReplenishRate()) {
      throw new IllegalArgumentException("burstCapacity must be greater than or equal to replenishRate");
    }
    if (entity.getRequestedTokens() == null || entity.getRequestedTokens() <= 0) {
      throw new IllegalArgumentException("requestedTokens must be greater than 0");
    }
    if (entity.getRequestedTokens() > entity.getBurstCapacity()) {
      throw new IllegalArgumentException("requestedTokens must be less than or equal to burstCapacity");
    }
  }

  private String normalizeLower(final String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.toLowerCase();
  }

  private String normalizeUpper(final String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.toUpperCase();
  }

  private String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
