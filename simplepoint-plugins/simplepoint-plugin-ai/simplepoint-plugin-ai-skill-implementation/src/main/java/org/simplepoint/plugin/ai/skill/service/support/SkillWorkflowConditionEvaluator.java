package org.simplepoint.plugin.ai.skill.service.support;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates and evaluates the bounded declarative Skill condition grammar.
 */
@Component
public class SkillWorkflowConditionEvaluator {

  private static final int MAXIMUM_DEPTH = 8;

  private static final int MAXIMUM_OPERANDS = 16;

  private static final Set<String> OPERATORS = Set.of(
      "all",
      "any",
      "equals",
      "isTrue",
      "not",
      "notEquals"
  );

  private final SkillWorkflowTemplateResolver templateResolver;

  /**
   * Creates the safe condition evaluator.
   */
  public SkillWorkflowConditionEvaluator(
      final SkillWorkflowTemplateResolver templateResolver
  ) {
    this.templateResolver = templateResolver;
  }

  /**
   * Validates a condition and all references it may read.
   */
  public void validate(
      final Object condition,
      final List<String> availableStepIds,
      final String label
  ) {
    validateNode(condition, availableStepIds, label, 0);
  }

  /**
   * Evaluates a previously validated condition without executing expressions.
   */
  public boolean evaluate(
      final Object condition,
      final Map<String, Object> input,
      final Map<String, Object> stepResults
  ) {
    return evaluateNode(condition, input, stepResults, 0);
  }

  private void validateNode(
      final Object condition,
      final List<String> availableStepIds,
      final String label,
      final int depth
  ) {
    assertDepth(depth);
    Operator operator = operator(condition, label);
    switch (operator.name()) {
      case "equals", "notEquals" -> {
        List<?> operands = exactOperands(operator.value(), 2, label);
        templateResolver.validateTemplate(
            operands.get(0),
            availableStepIds,
            label + "." + operator.name() + "[0]"
        );
        templateResolver.validateTemplate(
            operands.get(1),
            availableStepIds,
            label + "." + operator.name() + "[1]"
        );
      }
      case "isTrue" -> templateResolver.validateTemplate(
          operator.value(),
          availableStepIds,
          label + ".isTrue"
      );
      case "all", "any" -> {
        List<?> conditions = conditionList(operator.value(), label);
        for (int index = 0; index < conditions.size(); index++) {
          validateNode(
              conditions.get(index),
              availableStepIds,
              label + "." + operator.name() + "[" + index + "]",
              depth + 1
          );
        }
      }
      case "not" -> validateNode(
          operator.value(),
          availableStepIds,
          label + ".not",
          depth + 1
      );
      default -> throw unsupported(operator.name(), label);
    }
  }

  private boolean evaluateNode(
      final Object condition,
      final Map<String, Object> input,
      final Map<String, Object> stepResults,
      final int depth
  ) {
    assertDepth(depth);
    Operator operator = operator(condition, "Workflow condition");
    return switch (operator.name()) {
      case "equals" -> {
        List<?> operands = exactOperands(
            operator.value(),
            2,
            "Workflow condition"
        );
        yield Objects.deepEquals(
            resolve(operands.get(0), input, stepResults),
            resolve(operands.get(1), input, stepResults)
        );
      }
      case "notEquals" -> {
        List<?> operands = exactOperands(
            operator.value(),
            2,
            "Workflow condition"
        );
        yield !Objects.deepEquals(
            resolve(operands.get(0), input, stepResults),
            resolve(operands.get(1), input, stepResults)
        );
      }
      case "isTrue" -> requireBoolean(
          resolve(operator.value(), input, stepResults)
      );
      case "all" -> conditionList(
          operator.value(),
          "Workflow condition"
      ).stream().allMatch(value ->
          evaluateNode(value, input, stepResults, depth + 1));
      case "any" -> conditionList(
          operator.value(),
          "Workflow condition"
      ).stream().anyMatch(value ->
          evaluateNode(value, input, stepResults, depth + 1));
      case "not" -> !evaluateNode(
          operator.value(),
          input,
          stepResults,
          depth + 1
      );
      default -> throw unsupported(operator.name(), "Workflow condition");
    };
  }

  private Object resolve(
      final Object value,
      final Map<String, Object> input,
      final Map<String, Object> stepResults
  ) {
    return templateResolver.resolve(value, input, stepResults);
  }

  private static Operator operator(
      final Object condition,
      final String label
  ) {
    if (!(condition instanceof Map<?, ?> map) || map.size() != 1) {
      throw new IllegalArgumentException(
          label + " must contain exactly one boolean operator"
      );
    }
    Map.Entry<?, ?> entry = map.entrySet().iterator().next();
    if (!(entry.getKey() instanceof String name)
        || !OPERATORS.contains(name)) {
      throw unsupported(String.valueOf(entry.getKey()), label);
    }
    return new Operator(name, entry.getValue());
  }

  private static List<?> exactOperands(
      final Object value,
      final int size,
      final String label
  ) {
    if (!(value instanceof List<?> list) || list.size() != size) {
      throw new IllegalArgumentException(
          label + " comparison must contain exactly " + size + " operands"
      );
    }
    return list;
  }

  private static List<?> conditionList(
      final Object value,
      final String label
  ) {
    if (!(value instanceof List<?> list)
        || list.isEmpty()
        || list.size() > MAXIMUM_OPERANDS) {
      throw new IllegalArgumentException(
          label + " boolean group must contain between 1 and "
              + MAXIMUM_OPERANDS + " conditions"
      );
    }
    return list;
  }

  private static boolean requireBoolean(final Object value) {
    if (!(value instanceof Boolean result)) {
      throw new IllegalArgumentException(
          "Workflow isTrue operand must resolve to a boolean"
      );
    }
    return result;
  }

  private static void assertDepth(final int depth) {
    if (depth > MAXIMUM_DEPTH) {
      throw new IllegalArgumentException(
          "Workflow condition exceeds maximum nesting depth"
      );
    }
  }

  private static IllegalArgumentException unsupported(
      final String operator,
      final String label
  ) {
    return new IllegalArgumentException(
        label + " contains unsupported operator: " + operator
    );
  }

  private record Operator(String name, Object value) {
  }
}
