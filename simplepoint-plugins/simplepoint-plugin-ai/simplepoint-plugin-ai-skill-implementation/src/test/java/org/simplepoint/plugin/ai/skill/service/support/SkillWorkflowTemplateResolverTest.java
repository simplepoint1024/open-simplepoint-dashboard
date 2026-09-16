package org.simplepoint.plugin.ai.skill.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillWorkflowTemplateResolverTest {

  private final SkillWorkflowTemplateResolver resolver =
      new SkillWorkflowTemplateResolver();

  @Test
  void resolvesInputAndPriorStepReferences() {
    Map<String, Object> template = Map.of(
        "message", Map.of("$ref", "input.message"),
        "previous", Map.of("$ref", "steps.first.structuredContent.value")
    );

    resolver.validateTemplate(template, List.of("first"), "arguments");
    Object resolved = resolver.resolve(
        template,
        Map.of("message", "hello"),
        Map.of("first", Map.of(
            "structuredContent", Map.of("value", "world")
        ))
    );

    assertThat(resolved).isEqualTo(Map.of(
        "message", "hello",
        "previous", "world"
    ));
  }

  @Test
  void rejectsFutureStepReferences() {
    Map<String, Object> template = Map.of(
        "message", Map.of("$ref", "steps.future.value")
    );

    assertThatThrownBy(() ->
        resolver.validateTemplate(template, List.of("first"), "arguments"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unavailable");
  }
}
