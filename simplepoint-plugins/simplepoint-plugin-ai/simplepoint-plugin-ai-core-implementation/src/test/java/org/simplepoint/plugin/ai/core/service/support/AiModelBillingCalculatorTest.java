package org.simplepoint.plugin.ai.core.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiBillingStatus;

class AiModelBillingCalculatorTest {

  private final AiModelBillingCalculator calculator = new AiModelBillingCalculator();

  @Test
  void calculatesCachedAndRegularInputSeparately() {
    AiModelDefinition model = pricedModel();
    AiInvocationRecord record = new AiInvocationRecord();

    calculator.initialize(record, model);
    record.setInputTokens(1_200_000);
    record.setCachedInputTokens(200_000);
    record.setOutputTokens(300_000);
    calculator.calculate(record);

    assertThat(record.getBillingStatus()).isEqualTo(AiBillingStatus.CALCULATED);
    assertThat(record.getBillingCurrency()).isEqualTo("USD");
    assertThat(record.getInputCost()).isEqualByComparingTo("2.000000000000");
    assertThat(record.getCachedInputCost()).isEqualByComparingTo("0.200000000000");
    assertThat(record.getOutputCost()).isEqualByComparingTo("2.400000000000");
    assertThat(record.getRequestCost()).isEqualByComparingTo("0.010000000000");
    assertThat(record.getTotalCost()).isEqualByComparingTo("4.610000000000");
  }

  @Test
  void capsCachedTokensAtReportedInputTokens() {
    AiModelDefinition model = pricedModel();
    AiInvocationRecord record = new AiInvocationRecord();

    calculator.initialize(record, model);
    record.setInputTokens(100);
    record.setCachedInputTokens(200);
    calculator.calculate(record);

    assertThat(record.getInputCost()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(record.getCachedInputCost()).isEqualByComparingTo("0.000100000000");
  }

  @Test
  void leavesUnpricedInvocationWithoutAnAmount() {
    AiModelDefinition model = new AiModelDefinition();
    model.setBillingEnabled(Boolean.FALSE);
    AiInvocationRecord record = new AiInvocationRecord();

    calculator.initialize(record, model);
    calculator.calculate(record);

    assertThat(record.getBillingStatus()).isEqualTo(AiBillingStatus.UNPRICED);
    assertThat(record.getTotalCost()).isNull();
  }

  @Test
  void marksFailedPricedInvocationAsNotCharged() {
    AiInvocationRecord record = new AiInvocationRecord();
    calculator.initialize(record, pricedModel());

    calculator.markNotCharged(record);

    assertThat(record.getBillingStatus()).isEqualTo(AiBillingStatus.NOT_CHARGED);
    assertThat(record.getTotalCost()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  private static AiModelDefinition pricedModel() {
    AiModelDefinition model = new AiModelDefinition();
    model.setBillingEnabled(Boolean.TRUE);
    model.setBillingCurrency("USD");
    model.setInputTokenPrice(new BigDecimal("2.00"));
    model.setCachedInputTokenPrice(new BigDecimal("1.00"));
    model.setOutputTokenPrice(new BigDecimal("8.00"));
    model.setRequestPrice(new BigDecimal("0.01"));
    return model;
  }
}
