package org.simplepoint.plugin.ai.workflow.service.support;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evaluates the bounded declarative Workflow condition grammar.
 */
@Component
public class WorkflowConditionEvaluator {

  private static final int MAXIMUM_DEPTH = 8;

  private static final Set<String> OPERATORS = Set.of(
      "all",
      "any",
      "equals",
      "isTrue",
      "not",
      "notEquals"
  );

  private final WorkflowValueResolver resolver;

  /**
   * Creates the safe condition evaluator.
   */
  public WorkflowConditionEvaluator(
      final WorkflowValueResolver resolver
  ) {
    this.resolver = resolver;
  }

  /**
   * Evaluates a validated condition without executable expressions.
   */
  public boolean evaluate(
      final Object condition,
      final Map<String, Object> input,
      final Map<String, Object> nodeOutputs
  ) {
    return evaluateNode(condition, input, nodeOutputs, 0);
  }

  private boolean evaluateNode(
      final Object condition,
      final Map<String, Object> input,
      final Map<String, Object> nodeOutputs,
      final int depth
  ) {
    if (depth > MAXIMUM_DEPTH
        || !(condition instanceof Map<?, ?> map)
        || map.size() != 1) {
      throw new IllegalArgumentException(
          "Workflow condition is invalid"
      );
    }
    Map.Entry<?, ?> entry = map.entrySet().iterator().next();
    String operator = String.valueOf(entry.getKey());
    if (!OPERATORS.contains(operator)) {
      throw new IllegalArgumentException(
          "Workflow condition operator is unsupported"
      );
    }
    Object operand = entry.getValue();
    return switch (operator) {
      case "equals" -> compare(operand, input, nodeOutputs, true);
      case "notEquals" -> compare(operand, input, nodeOutputs, false);
      case "isTrue" -> {
        Object value = resolver.resolve(operand, input, nodeOutputs);
        if (!(value instanceof Boolean result)) {
          throw new IllegalArgumentException(
              "Workflow isTrue value must be boolean"
          );
        }
        yield result;
      }
      case "all" -> list(operand).stream().allMatch(value ->
          evaluateNode(value, input, nodeOutputs, depth + 1));
      case "any" -> list(operand).stream().anyMatch(value ->
          evaluateNode(value, input, nodeOutputs, depth + 1));
      case "not" -> !evaluateNode(
          operand,
          input,
          nodeOutputs,
          depth + 1
      );
      default -> throw new IllegalStateException(
          "Unsupported Workflow condition operator"
      );
    };
  }

  private boolean compare(
      final Object operand,
      final Map<String, Object> input,
      final Map<String, Object> nodeOutputs,
      final boolean expectedEqual
  ) {
    List<?> values = list(operand);
    if (values.size() != 2) {
      throw new IllegalArgumentException(
          "Workflow comparison requires two operands"
      );
    }
    boolean equal = Objects.deepEquals(
        resolver.resolve(values.get(0), input, nodeOutputs),
        resolver.resolve(values.get(1), input, nodeOutputs)
    );
    return expectedEqual == equal;
  }

  private static List<?> list(final Object value) {
    if (!(value instanceof List<?> list)
        || list.isEmpty()
        || list.size() > 16) {
      throw new IllegalArgumentException(
          "Workflow condition list is invalid"
      );
    }
    return list;
  }
}
