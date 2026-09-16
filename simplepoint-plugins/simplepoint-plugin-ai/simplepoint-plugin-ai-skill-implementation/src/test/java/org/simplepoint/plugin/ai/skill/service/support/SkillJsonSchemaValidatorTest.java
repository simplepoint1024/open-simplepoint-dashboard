package org.simplepoint.plugin.ai.skill.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillJsonSchemaValidatorTest {

  private final SkillJsonSchemaValidator validator =
      new SkillJsonSchemaValidator();

  @Test
  void validatesRequiredTypedProperties() {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "required", List.of("message"),
        "additionalProperties", false,
        "properties", Map.of(
            "message", Map.of("type", "string", "minLength", 1)
        )
    );

    assertThatCode(() ->
        validator.validate(schema, Map.of("message", "hello"), "input"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() ->
        validator.validate(schema, Map.of(), "input"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() ->
        validator.validate(schema, Map.of("message", 1), "input"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("string");
  }

  @Test
  void rejectsUnsupportedKeywordsInsteadOfSilentlyIgnoringConstraints() {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "oneOf", List.of(Map.of("required", List.of("message")))
    );

    assertThatThrownBy(() ->
        validator.validateSchema(schema, "Skill inputSchema"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported JSON Schema keyword oneOf");
    assertThat(validator.supports(schema)).isFalse();
    assertThat(validator.supports(Map.of("type", "object"))).isTrue();
  }

  @Test
  void rejectsInvalidBoundsAtPublicationTime() {
    Map<String, Object> schema = Map.of(
        "type", "string",
        "minLength", 10,
        "maxLength", 2
    );

    assertThatThrownBy(() ->
        validator.validateSchema(schema, "Skill outputSchema"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not exceed");
  }

  @Test
  void rejectsPathologicalSchemaWidthWithBoundedFailure() {
    Map<String, Object> properties = new LinkedHashMap<>();
    for (int index = 0; index < 1025; index++) {
      properties.put("field" + index, Map.of("type", "string"));
    }
    Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", properties
    );

    assertThatThrownBy(() ->
        validator.validateSchema(schema, "Skill inputSchema"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximum property count");
  }
}
