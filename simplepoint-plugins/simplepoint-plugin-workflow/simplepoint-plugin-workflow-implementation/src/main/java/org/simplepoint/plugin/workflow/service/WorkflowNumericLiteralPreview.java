package org.simplepoint.plugin.workflow.service;

import java.util.regex.Pattern;
import org.flowable.common.engine.impl.de.odysseus.el.util.SimpleContext;
import org.flowable.common.engine.impl.el.FlowableExpressionFactory;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.springframework.stereotype.Component;

/** Read-only numeric identity previews, with no variables, resolvers or application bindings. */
@Component
public class WorkflowNumericLiteralPreview {

  private static final Pattern NUMERIC_LITERAL = Pattern.compile(
      "\\$\\{\\s*-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\\s*\\}"
  );
  private final FlowableExpressionFactory factory = new FlowableExpressionFactory();

  /** Uses the same Number.toString identity conversion as Flowable task assignments. */
  public String preview(final String expression) {
    if (expression == null || expression.length() > 500 || !NUMERIC_LITERAL.matcher(expression.trim()).matches()) {
      throw new WorkflowException(Code.VALIDATION, "仅支持预览 500 字符以内的单个数字常量表达式");
    }
    try {
      var context = new SimpleContext();
      Object value = factory.createValueExpression(context, expression.trim(), Object.class).getValue(context);
      if (!(value instanceof Number)) {
        throw new WorkflowException(Code.VALIDATION, "表达式不是数字常量");
      }
      return value.toString();
    } catch (WorkflowException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new WorkflowException(Code.VALIDATION, "数字常量超出流程引擎支持的范围或格式不正确", exception);
    }
  }
}
