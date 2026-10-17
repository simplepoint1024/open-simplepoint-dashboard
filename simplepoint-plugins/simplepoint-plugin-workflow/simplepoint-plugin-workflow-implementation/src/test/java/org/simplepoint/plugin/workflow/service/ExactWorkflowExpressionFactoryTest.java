package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.flowable.common.engine.impl.de.odysseus.el.util.SimpleContext;
import org.junit.jupiter.api.Test;

class ExactWorkflowExpressionFactoryTest {
  @Test
  void thresholdsArithmeticAndMixedIntegerComparisonsRemainExact() {
    var factory = new ExactWorkflowExpressionFactory();
    var context = new SimpleContext();
    context.setVariable("amount", factory.createValueExpression(new BigDecimal("9007199254740993.01"), BigDecimal.class));
    for (String expression : new String[] {
        "${amount > 9007199254740993}", "${amount == 9007199254740993.01}",
        "${amount < 9007199254740993.02}", "${0.1 + 0.2 == 0.3}",
        "${9007199254740993.00 == 9007199254740993}",
        "${9007199254740993.00 <= 9007199254740993}"}) {
      assertThat(factory.createValueExpression(context, expression, Boolean.class).getValue(context))
          .as(expression).isEqualTo(true);
    }
    assertThat(factory.createValueExpression(context, "${9007199254740993.00 > 9007199254740993}", Boolean.class).getValue(context)).isEqualTo(false);
  }
}
