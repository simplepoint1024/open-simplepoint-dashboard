package org.simplepoint.plugin.ai.skill.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.AssertionOperator;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerDocument.TestAssertion;
import org.simplepoint.plugin.ai.skill.api.model.SkillTestAssertionResult;

class SkillMockAssertionEvaluatorTest {

  private final SkillMockAssertionEvaluator evaluator =
      new SkillMockAssertionEvaluator(new ObjectMapper());

  @Test
  void evaluatesOutputAndNodePathsInStableOrder() {
    List<TestAssertion> assertions = List.of(
        assertion("output", "__output", "message",
            AssertionOperator.EQUALS, "ok"),
        assertion("node", "tool-1", "structuredContent.items.1",
            AssertionOperator.CONTAINS, "two"),
        assertion("missing", "__output", "absent",
            AssertionOperator.NOT_EXISTS, null),
        assertion("pattern", "__output", "message",
            AssertionOperator.MATCHES, "^o.$")
    );

    List<SkillTestAssertionResult> results = evaluator.evaluate(
        assertions,
        Map.of("tool-1", Map.of(
            "structuredContent", Map.of(
                "items", List.of("one", List.of("two", "three"))
            )
        )),
        Map.of("message", "ok")
    );

    assertThat(results).extracting(SkillTestAssertionResult::id)
        .containsExactly("output", "node", "missing", "pattern");
    assertThat(results).allMatch(SkillTestAssertionResult::passed);
  }

  @Test
  void returnsExplainableFailuresWithoutDroppingActualValues() {
    List<SkillTestAssertionResult> results = evaluator.evaluate(
        List.of(
            assertion("wrong", "__output", "count",
                AssertionOperator.EQUALS, 3),
            assertion("unsafe", "__output", "name",
                AssertionOperator.MATCHES, "(a+)+")
        ),
        Map.of(),
        Map.of("count", 2, "name", "aaaa")
    );

    assertThat(results).allMatch(result -> !result.passed());
    assertThat(results.getFirst().actual()).isEqualTo(2);
    assertThat(results.getFirst().message())
        .isEqualTo("Actual value does not equal expected value");
    assertThat(results.getLast().message())
        .isEqualTo("Regular expression is invalid or unsafe");
  }

  private static TestAssertion assertion(
      final String id,
      final String source,
      final String path,
      final AssertionOperator operator,
      final Object expected
  ) {
    return new TestAssertion(id, source, path, operator, expected);
  }
}
