package org.simplepoint.plugin.ai.core.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiBillingStatus;
import org.simplepoint.plugin.ai.core.api.model.AiInvocationStatus;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiBillingAggregationRepository;
import org.simplepoint.plugin.ai.core.api.vo.AiBillingModels.CurrencySummary;
import org.simplepoint.plugin.ai.core.api.vo.AiBillingModels.ModelSummary;
import org.springframework.stereotype.Repository;

/** JPA billing aggregates that keep database-side grouping and currency separation. */
@Repository
public class JpaAiBillingAggregationRepository implements AiBillingAggregationRepository {

  private static final String SCOPE_PERIOD = """
      r.scopeType = :scopeType
      and ((:tenantId is null and r.tenantId is null) or r.tenantId = :tenantId)
      and r.startedAt >= :from and r.startedAt < :to
      and r.deletedAt is null
      """;

  private final EntityManager entityManager;

  /** Creates the database aggregation repository. */
  public JpaAiBillingAggregationRepository(final EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public long countInvocations(
      final AiResourceScope scopeType,
      final String tenantId,
      final Instant from,
      final Instant to
  ) {
    Query query = query(
        "select count(r) from AiInvocationRecord r where " + SCOPE_PERIOD,
        scopeType,
        tenantId,
        from,
        to
    );
    return number(query.getSingleResult());
  }

  @Override
  public long countPricedInvocations(
      final AiResourceScope scopeType,
      final String tenantId,
      final Instant from,
      final Instant to
  ) {
    Query query = query(
        "select count(r) from AiInvocationRecord r where " + SCOPE_PERIOD
            + " and r.billingStatus = :billingStatus",
        scopeType,
        tenantId,
        from,
        to
    );
    query.setParameter("billingStatus", AiBillingStatus.CALCULATED);
    return number(query.getSingleResult());
  }

  @Override
  public long countUnpricedInvocations(
      final AiResourceScope scopeType,
      final String tenantId,
      final Instant from,
      final Instant to
  ) {
    Query query = query(
        "select count(r) from AiInvocationRecord r where " + SCOPE_PERIOD
            + " and r.status = :invocationStatus"
            + " and (r.billingStatus is null or r.billingStatus = :billingStatus)",
        scopeType,
        tenantId,
        from,
        to
    );
    query.setParameter("invocationStatus", AiInvocationStatus.SUCCEEDED);
    query.setParameter("billingStatus", AiBillingStatus.UNPRICED);
    return number(query.getSingleResult());
  }

  @Override
  public List<CurrencySummary> summarizeByCurrency(
      final AiResourceScope scopeType,
      final String tenantId,
      final Instant from,
      final Instant to
  ) {
    Query query = query(
        """
        select r.billingCurrency, count(r),
          coalesce(sum(r.inputTokens), 0),
          coalesce(sum(r.cachedInputTokens), 0),
          coalesce(sum(r.outputTokens), 0),
          coalesce(sum(r.inputCost), 0),
          coalesce(sum(r.cachedInputCost), 0),
          coalesce(sum(r.outputCost), 0),
          coalesce(sum(r.requestCost), 0),
          coalesce(sum(r.totalCost), 0)
        from AiInvocationRecord r
        where""" + " " + SCOPE_PERIOD
            + " and r.billingStatus = :billingStatus"
            + " group by r.billingCurrency"
            + " order by sum(r.totalCost) desc",
        scopeType,
        tenantId,
        from,
        to
    );
    query.setParameter("billingStatus", AiBillingStatus.CALCULATED);
    return rows(query).stream().map(this::currencySummary).toList();
  }

  @Override
  public List<ModelSummary> summarizeByModel(
      final AiResourceScope scopeType,
      final String tenantId,
      final Instant from,
      final Instant to,
      final int limit
  ) {
    Query query = query(
        """
        select r.modelDefinitionId, r.modelId, r.billingCurrency, count(r),
          coalesce(sum(r.inputTokens), 0),
          coalesce(sum(r.outputTokens), 0),
          coalesce(sum(r.totalCost), 0)
        from AiInvocationRecord r
        where""" + " " + SCOPE_PERIOD
            + " and r.billingStatus = :billingStatus"
            + " group by r.modelDefinitionId, r.modelId, r.billingCurrency"
            + " order by sum(r.totalCost) desc",
        scopeType,
        tenantId,
        from,
        to
    );
    query.setParameter("billingStatus", AiBillingStatus.CALCULATED);
    query.setMaxResults(limit);
    return rows(query).stream().map(this::modelSummary).toList();
  }

  private Query query(
      final String jpql,
      final AiResourceScope scopeType,
      final String tenantId,
      final Instant from,
      final Instant to
  ) {
    return entityManager.createQuery(jpql)
        .setParameter("scopeType", scopeType)
        .setParameter("tenantId", tenantId)
        .setParameter("from", from)
        .setParameter("to", to);
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> rows(final Query query) {
    return query.getResultList();
  }

  private CurrencySummary currencySummary(final Object[] row) {
    return new CurrencySummary(
        String.valueOf(row[0]),
        number(row[1]),
        number(row[2]),
        number(row[3]),
        number(row[4]),
        decimal(row[5]),
        decimal(row[6]),
        decimal(row[7]),
        decimal(row[8]),
        decimal(row[9])
    );
  }

  private ModelSummary modelSummary(final Object[] row) {
    return new ModelSummary(
        String.valueOf(row[0]),
        String.valueOf(row[1]),
        String.valueOf(row[2]),
        number(row[3]),
        number(row[4]),
        number(row[5]),
        decimal(row[6])
    );
  }

  private static long number(final Object value) {
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static BigDecimal decimal(final Object value) {
    return value instanceof BigDecimal decimal ? decimal : BigDecimal.ZERO;
  }
}
