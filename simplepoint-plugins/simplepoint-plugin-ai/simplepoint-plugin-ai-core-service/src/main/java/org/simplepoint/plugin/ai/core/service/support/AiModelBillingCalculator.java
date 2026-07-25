package org.simplepoint.plugin.ai.core.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiBillingStatus;
import org.springframework.stereotype.Component;

/**
 * Snapshots model pricing and calculates invocation cost from provider-reported token usage.
 */
@Component
public class AiModelBillingCalculator {

  private static final BigDecimal TOKEN_PRICE_UNIT = BigDecimal.valueOf(1_000_000L);

  private static final int COST_SCALE = 12;

  /** Copies the effective pricing policy onto a new invocation record. */
  public void initialize(
      final AiInvocationRecord record,
      final AiModelDefinition model
  ) {
    if (!Boolean.TRUE.equals(model.getBillingEnabled())) {
      record.setBillingStatus(AiBillingStatus.UNPRICED);
      return;
    }
    BigDecimal inputPrice = zero(model.getInputTokenPrice());
    record.setBillingStatus(AiBillingStatus.PENDING);
    record.setBillingCurrency(model.getBillingCurrency());
    record.setInputUnitPrice(inputPrice);
    record.setCachedInputUnitPrice(
        model.getCachedInputTokenPrice() == null
            ? inputPrice : model.getCachedInputTokenPrice()
    );
    record.setOutputUnitPrice(zero(model.getOutputTokenPrice()));
    record.setRequestUnitPrice(zero(model.getRequestPrice()));
  }

  /** Calculates component and total costs, keeping cached input separate from regular input. */
  public void calculate(final AiInvocationRecord record) {
    if (record.getBillingStatus() != AiBillingStatus.PENDING) {
      return;
    }
    long inputTokens = positive(record.getInputTokens());
    long cachedTokens = Math.min(inputTokens, positive(record.getCachedInputTokens()));
    long regularInputTokens = inputTokens - cachedTokens;
    long outputTokens = positive(record.getOutputTokens());

    record.setInputCost(tokenCost(record.getInputUnitPrice(), regularInputTokens));
    record.setCachedInputCost(
        tokenCost(record.getCachedInputUnitPrice(), cachedTokens)
    );
    record.setOutputCost(tokenCost(record.getOutputUnitPrice(), outputTokens));
    record.setRequestCost(cost(zero(record.getRequestUnitPrice())));
    record.setTotalCost(
        record.getInputCost()
            .add(record.getCachedInputCost())
            .add(record.getOutputCost())
            .add(record.getRequestCost())
            .setScale(COST_SCALE, RoundingMode.HALF_UP)
    );
    record.setBillingStatus(AiBillingStatus.CALCULATED);
  }

  /** Marks a priced invocation as non-chargeable after failure or cancellation. */
  public void markNotCharged(final AiInvocationRecord record) {
    if (record.getBillingStatus() != AiBillingStatus.PENDING) {
      return;
    }
    record.setInputCost(cost(BigDecimal.ZERO));
    record.setCachedInputCost(cost(BigDecimal.ZERO));
    record.setOutputCost(cost(BigDecimal.ZERO));
    record.setRequestCost(cost(BigDecimal.ZERO));
    record.setTotalCost(cost(BigDecimal.ZERO));
    record.setBillingStatus(AiBillingStatus.NOT_CHARGED);
  }

  private static BigDecimal tokenCost(final BigDecimal price, final long tokens) {
    if (tokens == 0L) {
      return cost(BigDecimal.ZERO);
    }
    return zero(price)
        .multiply(BigDecimal.valueOf(tokens))
        .divide(TOKEN_PRICE_UNIT, COST_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal cost(final BigDecimal value) {
    return value.setScale(COST_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero(final BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static long positive(final Integer value) {
    return value == null ? 0L : Math.max(0L, value.longValue());
  }
}
