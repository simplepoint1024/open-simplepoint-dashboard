package org.simplepoint.plugin.ai.core.api.repository;

import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.vo.AiBillingModels.CurrencySummary;
import org.simplepoint.plugin.ai.core.api.vo.AiBillingModels.ModelSummary;

/** Database aggregation contract for model billing reports. */
public interface AiBillingAggregationRepository {

  /** Counts all invocation states in the requested scope and period. */
  long countInvocations(
      AiResourceScope scopeType,
      String tenantId,
      Instant from,
      Instant to
  );

  /** Counts invocations with a calculated cost. */
  long countPricedInvocations(
      AiResourceScope scopeType,
      String tenantId,
      Instant from,
      Instant to
  );

  /** Counts successful invocations without a pricing snapshot. */
  long countUnpricedInvocations(
      AiResourceScope scopeType,
      String tenantId,
      Instant from,
      Instant to
  );

  /** Aggregates priced invocations by ISO currency code. */
  List<CurrencySummary> summarizeByCurrency(
      AiResourceScope scopeType,
      String tenantId,
      Instant from,
      Instant to
  );

  /** Returns the highest-cost model and currency groups. */
  List<ModelSummary> summarizeByModel(
      AiResourceScope scopeType,
      String tenantId,
      Instant from,
      Instant to,
      int limit
  );
}
