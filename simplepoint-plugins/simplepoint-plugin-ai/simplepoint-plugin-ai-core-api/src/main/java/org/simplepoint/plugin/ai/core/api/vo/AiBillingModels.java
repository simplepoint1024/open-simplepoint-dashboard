package org.simplepoint.plugin.ai.core.api.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Scope-isolated model billing report contracts. */
public final class AiBillingModels {

  private AiBillingModels() {
  }

  /** Billing totals for one currency. Different currencies are never combined. */
  public record CurrencySummary(
      String currency,
      long invocationCount,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      BigDecimal inputCost,
      BigDecimal cachedInputCost,
      BigDecimal outputCost,
      BigDecimal requestCost,
      BigDecimal totalCost
  ) {
  }

  /** Per-model billing totals within one currency. */
  public record ModelSummary(
      String modelDefinitionId,
      String modelId,
      String currency,
      long invocationCount,
      long inputTokens,
      long outputTokens,
      BigDecimal totalCost
  ) {
  }

  /** Complete billing overview for a validated time range. */
  public record BillingSummary(
      Instant from,
      Instant to,
      long invocationCount,
      long pricedInvocationCount,
      long unpricedInvocationCount,
      List<CurrencySummary> currencies,
      List<ModelSummary> models
  ) {
  }
}
