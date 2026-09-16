package org.simplepoint.plugin.ai.skill.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillWorkflowConditionEvaluatorTest {

  private SkillWorkflowConditionEvaluator evaluator;

  @BeforeEach
  void setUp() {
    evaluator = new SkillWorkflowConditionEvaluator(
        new SkillWorkflowTemplateResolver()
    );
  }

  @Test
  void evaluatesNestedBoundedBooleanGrammar() {
    Object condition = Map.of(
        "all",
        List.of(
            Map.of(
                "equals",
                List.of(Map.of("$ref", "input.mode"), "full")
            ),
            Map.of(
                "not",
                Map.of(
                    "isTrue",
                    Map.of("$ref", "steps.precheck.structuredContent.blocked")
                )
            )
        )
    );

    evaluator.validate(condition, List.of("precheck"), "Condition");

    assertThat(evaluator.evaluate(
        condition,
        Map.of("mode", "full"),
        Map.of(
            "precheck",
            Map.of(
                "structuredContent",
                Map.of("blocked", false)
            )
        )
    )).isTrue();
  }

  @Test
  void rejectsReferencesToUnavailableSteps() {
    Object condition = Map.of(
        "equals",
        List.of(Map.of("$ref", "steps.future.value"), "value")
    );

    assertThatThrownBy(() ->
        evaluator.validate(condition, List.of(), "Condition"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unavailable workflow data");
  }

  @Test
  void rejectsNonBooleanIsTrueOperands() {
    Object condition = Map.of(
        "isTrue",
        Map.of("$ref", "input.value")
    );

    assertThatThrownBy(() -> evaluator.evaluate(
        condition,
        Map.of("value", "true"),
        Map.of()
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must resolve to a boolean");
  }
}
