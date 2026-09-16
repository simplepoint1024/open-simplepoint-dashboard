package org.simplepoint.plugin.ai.agent.service.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSkillInputContractTest {

  @Test
  void enrichesAndEnforcesPinnedResourceTemplateInput() {
    AgentSkillInputContract contract = AgentSkillInputContract.create(
        inputSchema(),
        manifest()
    );

    @SuppressWarnings("unchecked")
    Map<String, Object> properties =
        (Map<String, Object>) contract.schema().get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> uri =
        (Map<String, Object>) properties.get("uri");

    assertThat(uri.get("description").toString())
        .contains("document://{documentId}");
    assertThat(uri.get("examples"))
        .isEqualTo(List.of("document://documentId"));
    assertThatCode(() -> contract.validate(Map.of(
        "message", "hello",
        "uri", "document://getting-started"
    ))).doesNotThrowAnyException();
    assertThatThrownBy(() -> contract.validate(Map.of(
        "message", "hello",
        "uri", "skill/echo"
    ))).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("document://{documentId}");
  }

  @Test
  void ignoresResourceTemplatesNotDrivenDirectlyByAgentInput() {
    Map<String, Object> manifest = manifest();
    @SuppressWarnings("unchecked")
    Map<String, Object> spec =
        (Map<String, Object>) manifest.get("spec");
    @SuppressWarnings("unchecked")
    Map<String, Object> workflow =
        (Map<String, Object>) spec.get("workflow");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> steps =
        (List<Map<String, Object>>) workflow.get("steps");
    steps.getFirst().put("uri", "document://fixed");

    AgentSkillInputContract contract = AgentSkillInputContract.create(
        inputSchema(),
        manifest
    );

    assertThatCode(() -> contract.validate(Map.of(
        "message", "hello",
        "uri", "skill/echo"
    ))).doesNotThrowAnyException();
  }

  private static Map<String, Object> inputSchema() {
    return Map.of(
        "type", "object",
        "additionalProperties", false,
        "required", List.of("message", "uri"),
        "properties", Map.of(
            "message", Map.of("type", "string"),
            "uri", Map.of("type", "string")
        )
    );
  }

  private static Map<String, Object> manifest() {
    return new java.util.LinkedHashMap<>(Map.of(
        "spec", new java.util.LinkedHashMap<>(Map.of(
            "resources", List.of(Map.of(
                "alias", "document",
                "uriTemplate", "document://{documentId}"
            )),
            "workflow", new java.util.LinkedHashMap<>(Map.of(
                "steps", new java.util.ArrayList<>(List.of(
                    new java.util.LinkedHashMap<>(Map.of(
                        "id", "resource-step",
                        "type", "resource",
                        "resource", "document",
                        "uri", Map.of("$ref", "input.uri")
                    ))
                ))
            ))
        ))
    ));
  }
}
