package org.simplepoint.plugin.ai.skill.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.AssertionOperator;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestAssertion;
import org.simplepoint.plugin.ai.skill.api.model.SkillTestAssertionResult;
import org.springframework.stereotype.Component;

/** Evaluates pinned Designer assertions without mutating workflow results. */
@Component
public class SkillMockAssertionEvaluator {

  private static final int MAXIMUM_PATTERN_LENGTH = 128;

  private static final int MAXIMUM_MATCH_VALUE_LENGTH = 1024;

  private static final Pattern QUANTIFIED_GROUP = Pattern.compile(
      "\\([^)]*\\)[*+{]"
  );

  private static final Pattern BACK_REFERENCE = Pattern.compile(
      "\\\\[1-9]"
  );

  private final ObjectMapper objectMapper;

  /** Creates a deterministic JSON-aware evaluator. */
  public SkillMockAssertionEvaluator(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Evaluates all assertions in their stable document order. */
  public List<SkillTestAssertionResult> evaluate(
      final List<TestAssertion> assertions,
      final Map<String, Object> nodeResults,
      final Object workflowOutput
  ) {
    List<SkillTestAssertionResult> results = new ArrayList<>();
    for (TestAssertion assertion : assertions) {
      Object source = "__output".equals(assertion.sourceNodeId())
          ? workflowOutput : nodeResults.get(assertion.sourceNodeId());
      Resolution resolution = resolve(source, assertion.fieldPath());
      Evaluation evaluation = evaluate(
          assertion.operator(),
          resolution,
          assertion.expected()
      );
      results.add(new SkillTestAssertionResult(
          assertion.id(),
          assertion.sourceNodeId(),
          assertion.fieldPath(),
          assertion.operator().name(),
          evaluation.passed(),
          assertion.expected(),
          resolution.found() ? resolution.value() : null,
          evaluation.message()
      ));
    }
    return List.copyOf(results);
  }

  private Evaluation evaluate(
      final AssertionOperator operator,
      final Resolution resolution,
      final Object expected
  ) {
    if (operator == AssertionOperator.EXISTS) {
      return outcome(resolution.found(), "Path does not exist");
    }
    if (operator == AssertionOperator.NOT_EXISTS) {
      return outcome(!resolution.found(), "Path exists");
    }
    if (!resolution.found()) {
      return new Evaluation(false, "Path does not exist");
    }
    return switch (operator) {
      case EQUALS -> outcome(jsonEquals(resolution.value(), expected),
          "Actual value does not equal expected value");
      case NOT_EQUALS -> outcome(!jsonEquals(resolution.value(), expected),
          "Actual value equals the excluded value");
      case CONTAINS -> outcome(contains(resolution.value(), expected),
          "Actual value does not contain expected value");
      case MATCHES -> matches(resolution.value(), expected);
      case EXISTS, NOT_EXISTS -> throw new IllegalStateException(
          "Existence assertion was not handled"
      );
    };
  }

  private Evaluation matches(final Object actual, final Object expected) {
    if (!(actual instanceof String value)
        || !(expected instanceof String expression)) {
      return new Evaluation(
          false,
          "MATCHES requires string actual and expected values"
      );
    }
    if (!safeRegex(expression, value)) {
      return new Evaluation(false, "Regular expression is invalid or unsafe");
    }
    try {
      return outcome(
          Pattern.compile(expression).matcher(value).matches(),
          "Actual value does not match the regular expression"
      );
    } catch (PatternSyntaxException exception) {
      return new Evaluation(false, "Regular expression is invalid or unsafe");
    }
  }

  private static boolean safeRegex(
      final String expression,
      final String value
  ) {
    if (expression.length() > MAXIMUM_PATTERN_LENGTH
        || value.length() > MAXIMUM_MATCH_VALUE_LENGTH
        || expression.contains("(?")
        || BACK_REFERENCE.matcher(expression).find()
        || QUANTIFIED_GROUP.matcher(expression).find()) {
      return false;
    }
    int quantifiers = 0;
    boolean escaped = false;
    for (int index = 0; index < expression.length(); index++) {
      char current = expression.charAt(index);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (current == '\\') {
        escaped = true;
      } else if (current == '*' || current == '+' || current == '{') {
        quantifiers++;
      }
    }
    return quantifiers <= 8;
  }

  private boolean contains(final Object actual, final Object expected) {
    if (actual instanceof String value && expected instanceof String nested) {
      return value.contains(nested);
    }
    if (actual instanceof Collection<?> values) {
      return values.stream().anyMatch(value -> jsonEquals(value, expected));
    }
    if (actual instanceof Map<?, ?> values) {
      if (expected instanceof String key) {
        return values.containsKey(key);
      }
      if (expected instanceof Map<?, ?> expectedEntries) {
        return expectedEntries.entrySet().stream().allMatch(entry ->
            values.containsKey(entry.getKey())
                && jsonEquals(values.get(entry.getKey()), entry.getValue()));
      }
    }
    return false;
  }

  private boolean jsonEquals(final Object left, final Object right) {
    JsonNode leftNode = objectMapper.valueToTree(left);
    JsonNode rightNode = objectMapper.valueToTree(right);
    return Objects.equals(leftNode, rightNode);
  }

  private static Resolution resolve(
      final Object source,
      final String rawPath
  ) {
    if (rawPath == null || rawPath.isBlank()) {
      return new Resolution(true, source);
    }
    Object current = source;
    for (String token : rawPath.split("\\.")) {
      if (current instanceof Map<?, ?> map && map.containsKey(token)) {
        current = map.get(token);
        continue;
      }
      if (current instanceof List<?> list) {
        try {
          int index = Integer.parseInt(token);
          if (index >= 0 && index < list.size()) {
            current = list.get(index);
            continue;
          }
        } catch (NumberFormatException ignored) {
          // A non-numeric token cannot address an array element.
        }
      }
      return new Resolution(false, null);
    }
    return new Resolution(true, current);
  }

  private static Evaluation outcome(
      final boolean passed,
      final String failureMessage
  ) {
    return new Evaluation(
        passed,
        passed ? "Assertion passed" : failureMessage
    );
  }

  private record Resolution(boolean found, Object value) {
  }

  private record Evaluation(boolean passed, String message) {
  }
}
