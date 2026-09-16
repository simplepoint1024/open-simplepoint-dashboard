package org.simplepoint.plugin.ai.skill.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.properties.SkillExecutionProperties;

class SkillBudgetPolicyTest {

  private SkillBudgetPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new SkillBudgetPolicy(
        new SkillExecutionProperties(),
        new ObjectMapper()
    );
  }

  @Test
  void defaultsToolCallsToImmutableWorkflowStepCount() {
    var budget = policy.normalize(null, 3);

    assertThat(budget.maximumToolCalls()).isEqualTo(3);
    assertThat(budget.maximumDurationSeconds()).isEqualTo(300);
    assertThat(budget.maximumPayloadBytes()).isEqualTo(1024L * 1024L);
  }

  @Test
  void rejectsBudgetThatCannotCoverWorkflow() {
    assertThatThrownBy(() -> policy.normalize(
        Map.of("maximumToolCalls", 1),
        2
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cover the worst Tool call path");
  }

  @Test
  void rejectsUnknownBudgetFieldsAndPlatformLimitViolations() {
    assertThatThrownBy(() -> policy.normalize(
        Map.of("maximumParallelism", 2),
        1
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported field");

    assertThatThrownBy(() -> policy.normalize(
        Map.of("maximumDurationSeconds", 3601),
        1
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("platform limit");
  }
}
