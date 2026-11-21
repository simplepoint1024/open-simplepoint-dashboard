package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordReferenceRuleTest {
  @Test
  void legacyDefinitionRetainsItsExactShapeAndReferenceOnlyPolicyIsNotEmpty() throws Exception {
    var json = new ObjectMapper();
    var legacy = new RecordCalculationDefinition("1", Map.of(), Map.of());
    assertThat(json.writeValueAsString(legacy))
        .isEqualTo("{\"languageVersion\":\"1\",\"formulas\":{},\"details\":{}}");
    var list = new ArrayList<>(List.of(rule("customer", "customer")));
    var policy = definition(list);
    list.clear();
    assertThat(policy.isEmpty()).isFalse();
    assertThat(policy.referencesOrEmpty()).hasSize(1);
    assertThat(json.readValue(json.writeValueAsString(policy), RecordCalculationDefinition.class))
        .isEqualTo(policy);
    assertThat(json.writeValueAsString(policy)).doesNotContain("eligibility", "recheckWhen");
    assertThatThrownBy(() -> policy.references().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsAmbiguousRelationsAndBoundedDefinitionOverflow() {
    assertThatThrownBy(() -> definition(List.of(rule("same", "first"), rule("same", "second"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> definition(List.of(rule("first", "same"), rule("second", "same"))))
        .isInstanceOf(IllegalArgumentException.class);
    var tooMany =
        java.util.stream.IntStream.range(0, 51)
            .mapToObj(index -> rule("r" + index, "f" + index))
            .toList();
    assertThatThrownBy(() -> definition(tooMany)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RecordReferenceRule(
                    "2",
                    "relation",
                    new RecordValidationField("parent", "field"),
                    "target",
                    "app",
                    "form",
                    "resource",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RecordReferenceRule(
                    "1",
                    "relation",
                    new RecordValidationField("parent", "field"),
                    "target",
                    "app",
                    "form",
                    " resource ",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordReferenceRule rule(String relation, String field) {
    return new RecordReferenceRule(
        "1",
        relation,
        new RecordValidationField("parent", field),
        "target",
        "app",
        "form",
        "resource",
        null,
        null);
  }

  private static RecordCalculationDefinition definition(List<RecordReferenceRule> rules) {
    return new RecordCalculationDefinition("1", Map.of(), Map.of(), null, null, null, null, rules);
  }
}
