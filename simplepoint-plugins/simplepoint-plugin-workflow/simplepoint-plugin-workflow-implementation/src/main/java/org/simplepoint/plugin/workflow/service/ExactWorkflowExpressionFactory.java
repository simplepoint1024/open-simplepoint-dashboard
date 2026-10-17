package org.simplepoint.plugin.workflow.service;

import java.math.BigDecimal;
import java.util.Properties;
import org.flowable.common.engine.impl.de.odysseus.el.misc.TypeConverter;
import org.flowable.common.engine.impl.de.odysseus.el.misc.TypeConverterImpl;
import org.flowable.common.engine.impl.de.odysseus.el.tree.TreeBuilder;
import org.flowable.common.engine.impl.de.odysseus.el.tree.impl.Builder;
import org.flowable.common.engine.impl.de.odysseus.el.tree.impl.Parser;
import org.flowable.common.engine.impl.el.FlowableExpressionFactory;

/**
 * Preserves decimal literals and integer-to-decimal coercion in the standard Flowable EL parser.
 */
public final class ExactWorkflowExpressionFactory extends FlowableExpressionFactory {
  @Override
  protected TreeBuilder createDefaultTreeBuilder(Builder.Feature... features) {
    return new ExactBuilder(features);
  }

  @Override
  protected TypeConverter createTypeConverter(Properties properties) {
    return new ExactConverter();
  }

  private static final class ExactBuilder extends FlowableExpressionBuilder {
    private ExactBuilder(Builder.Feature... features) {
      super(features);
    }

    @Override
    protected Parser createParser(String expression) {
      return new ExactParser(this, expression);
    }
  }

  private static final class ExactParser extends FlowableExpressionParserImpl {
    private ExactParser(ExactBuilder builder, String expression) {
      super(builder, expression);
    }

    @Override
    protected Number parseFloat(String literal) {
      // Parsing to Double first would permanently round a high-precision threshold.
      return new BigDecimal(literal);
    }
  }

  private static final class ExactConverter extends TypeConverterImpl {
    private static final long serialVersionUID = 1L;

    @Override
    protected BigDecimal coerceToBigDecimal(Object value) {
      // Upstream EL converts even Long through doubleValue(), losing integers above 2^53.
      return value instanceof Number number
          ? new BigDecimal(number.toString())
          : super.coerceToBigDecimal(value);
    }
  }
}
