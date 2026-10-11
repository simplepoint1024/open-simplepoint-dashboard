package org.simplepoint.plugin.workflow.service;

import java.util.regex.Pattern;
import org.flowable.common.engine.impl.de.odysseus.el.util.SimpleContext;
import org.flowable.common.engine.impl.el.FlowableExpressionFactory;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.WorkflowException.Code;
import org.springframework.stereotype.Component;

/** Native task-priority conversion for bounded constants, without variable or application bindings. */
@Component
public class WorkflowPriorityLiteralPreview {

  private static final String QUOTED = "\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'";
  private static final Pattern SCALAR = Pattern.compile(
      "\\$\\{\\s*(?:-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null|"
          + QUOTED + ")\\s*\\}"
  );
  private final FlowableExpressionFactory factory = new FlowableExpressionFactory();

  /** Classifies constants without evaluating any executable surface. */
  public boolean isLiteral(final String source) {
    return source == null || source.length() <= 500 && (!source.contains("${") && !source.contains("#{")
        || SCALAR.matcher(source.trim()).matches());
  }

  /** Mirrors handlePriority: String uses Integer.valueOf, Number uses intValue, null keeps the engine default. */
  public PriorityPreview preview(final String source) {
    if (!isLiteral(source)) {
      throw new WorkflowException(Code.VALIDATION, "仅支持预览 500 字符以内的优先级常量，不支持变量或复合表达式");
    }
    String text = source == null ? "" : source.trim();
    if (text.isEmpty()) {
      return new PriorityPreview(null, null);
    }
    try {
      Object value = text;
      if (text.startsWith("${")) {
        var context = new SimpleContext();
        value = factory.createValueExpression(context, text, Object.class).getValue(context);
      }
      if (value == null) {
        return new PriorityPreview(null, null);
      }
      if (value instanceof Number number) {
        return new PriorityPreview(Integer.toString(number.intValue()), null);
      }
      if (value instanceof String string) {
        return new PriorityPreview(Integer.valueOf(string).toString(), null);
      }
      return new PriorityPreview(null, "PRIORITY_TYPE");
    } catch (NumberFormatException exception) {
      return new PriorityPreview(null, "PRIORITY_INTEGER");
    } catch (RuntimeException exception) {
      throw new WorkflowException(Code.VALIDATION, "优先级常量表达式格式不正确", exception);
    }
  }

  /** Machine-readable issue codes are translated by the owning frontend. */
  public record PriorityPreview(String value, String issue) {
  }
}
