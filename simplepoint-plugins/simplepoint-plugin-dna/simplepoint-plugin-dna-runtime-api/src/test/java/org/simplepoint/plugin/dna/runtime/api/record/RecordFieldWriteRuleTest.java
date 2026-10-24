package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordFieldWriteRuleTest {
  @Test
  void preservesLegacySerializationAndCanonicalizesFrozenStates() throws Exception {
    var mapper = new ObjectMapper();
    var old = new RecordCalculationDefinition("1", Map.of(), Map.of());
    assertThat(mapper.writeValueAsString(old)).doesNotContain("writeRules");
    var rule = new RecordFieldWriteRule(new RecordValidationField("lines", "price"), false, List.of("SUBMITTED", "DRAFT"));
    assertThat(rule.updateStates()).containsExactly("DRAFT", "SUBMITTED");
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of(), null, null, null, List.of(rule));
    assertThat(definition.isEmpty()).isFalse();
    assertThat(mapper.readValue(mapper.writeValueAsBytes(definition), RecordCalculationDefinition.class)).isEqualTo(definition);
    assertThatThrownBy(() -> definition.writeRules().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> rule.updateStates().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsDuplicateTargetsStatesAndInvalidStateIdentifiers() {
    var field = new RecordValidationField("parent", "amount");
    var rule = new RecordFieldWriteRule(field, true, List.of());
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(), null, null, null, List.of(rule, rule)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordFieldWriteRule(field, true, List.of("DRAFT", "DRAFT"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordFieldWriteRule(field, true, List.of("invalid state"))).isInstanceOf(IllegalArgumentException.class);
  }
}
