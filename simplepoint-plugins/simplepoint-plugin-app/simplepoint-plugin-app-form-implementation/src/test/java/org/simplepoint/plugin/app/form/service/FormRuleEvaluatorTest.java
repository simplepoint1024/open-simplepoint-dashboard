package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;

/** Shared fixtures are also executed by the browser runtime's model tests. */
class FormRuleEvaluatorTest {

  @Test
  void evaluatesSharedTypedRuleContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (var input = getClass().getResourceAsStream("/form-condition-cases.json")) {
      assertThat(input).isNotNull();
      for (var item : mapper.readTree(input)) {
        FormCondition condition = mapper.treeToValue(item.get("condition"), FormCondition.class);
        Map<String, Object> values = mapper.convertValue(item.get("values"), new TypeReference<>() {});
        Map<String, FormFieldType> types = item.has("types")
            ? mapper.convertValue(item.get("types"), new TypeReference<>() {}) : Map.of();
        assertThat(FormRuleEvaluator.matches(condition, values, types))
            .as(item.get("name").asText()).isEqualTo(item.get("expected").asBoolean());
      }
    }
  }
}
