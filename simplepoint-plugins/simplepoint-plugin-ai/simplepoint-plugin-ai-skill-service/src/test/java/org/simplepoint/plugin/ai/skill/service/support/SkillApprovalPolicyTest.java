package org.simplepoint.plugin.ai.skill.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillApprovalPolicyTest {

  private final SkillApprovalPolicy policy =
      new SkillApprovalPolicy(new ObjectMapper());

  @Test
  void defaultsToNoApproval() {
    assertThat(policy.normalize(null)).satisfies(result -> {
      assertThat(result.required()).isFalse();
      assertThat(result.allowSelfApproval()).isFalse();
      assertThat(result.instructions()).isNull();
    });
  }

  @Test
  void normalizesExplicitExecutionApproval() {
    assertThat(policy.normalize(Map.of(
        "execution",
        Map.of(
            "required",
            true,
            "allowSelfApproval",
            false,
            "instructions",
            "Review requested data access"
        )
    ))).satisfies(result -> {
      assertThat(result.required()).isTrue();
      assertThat(result.allowSelfApproval()).isFalse();
      assertThat(result.instructions())
          .isEqualTo("Review requested data access");
    });
  }

  @Test
  void rejectsUnsupportedOrUnsafePolicyFields() {
    assertThatThrownBy(() -> policy.normalize(Map.of(
        "execution",
        Map.of("required", false, "allowSelfApproval", true)
    ))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only be enabled");
    assertThatThrownBy(() -> policy.normalize(Map.of("steps", Map.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported field");
  }
}
