package org.simplepoint.plugin.ai.core.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.simplepoint.plugin.ai.core.api.repository.AiBillingAggregationRepository;
import org.simplepoint.plugin.ai.core.api.service.AiBillingQueryService;
import org.simplepoint.plugin.ai.core.api.vo.AiBillingModels.BillingSummary;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Billing overview service with period validation and management-scope isolation. */
@Service
public class AiBillingQueryServiceImpl implements AiBillingQueryService {

  private static final Duration MAX_PERIOD = Duration.ofDays(366);

  private static final int MODEL_LIMIT = 20;

  private final AiBillingAggregationRepository repository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  /** Creates the billing overview service. */
  public AiBillingQueryServiceImpl(
      final AiBillingAggregationRepository repository,
      final AiScopeAccessPolicy scopeAccessPolicy
  ) {
    this.repository = repository;
    this.scopeAccessPolicy = scopeAccessPolicy;
  }

  @Override
  @Transactional(readOnly = true)
  public BillingSummary summarize(final Instant from, final Instant to) {
    Instant effectiveTo = to == null ? Instant.now() : to;
    Instant effectiveFrom = from == null ? startOfUtcMonth(effectiveTo) : from;
    validatePeriod(effectiveFrom, effectiveTo);
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    return new BillingSummary(
        effectiveFrom,
        effectiveTo,
        repository.countInvocations(
            scope.scopeType(), scope.tenantId(), effectiveFrom, effectiveTo
        ),
        repository.countPricedInvocations(
            scope.scopeType(), scope.tenantId(), effectiveFrom, effectiveTo
        ),
        repository.countUnpricedInvocations(
            scope.scopeType(), scope.tenantId(), effectiveFrom, effectiveTo
        ),
        repository.summarizeByCurrency(
            scope.scopeType(), scope.tenantId(), effectiveFrom, effectiveTo
        ),
        repository.summarizeByModel(
            scope.scopeType(), scope.tenantId(), effectiveFrom, effectiveTo, MODEL_LIMIT
        )
    );
  }

  private static Instant startOfUtcMonth(final Instant value) {
    return ZonedDateTime.ofInstant(value, ZoneOffset.UTC)
        .withDayOfMonth(1)
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant();
  }

  private static void validatePeriod(final Instant from, final Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("计费开始时间必须早于结束时间");
    }
    if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
      throw new IllegalArgumentException("单次计费汇总时间范围不能超过 366 天");
    }
  }
}
