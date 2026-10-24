package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class RecordValidationRuleTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final RecordValidationCondition always = new RecordValidationCondition(RecordValueConditionOperator.TRUE, null, List.of(), List.of());
  private final RecordValidationField total = new RecordValidationField("parent", "total-ID");

  @Test
  void preservesLegacyBytesAndRoundTripsImmutableStableValidationIdentities() throws Exception {
    var legacy = new RecordCalculationDefinition("1", Map.of(), Map.of());
    assertThat(mapper.writeValueAsString(legacy)).isEqualTo("{\"languageVersion\":\"1\",\"formulas\":{},\"details\":{}}");
    var rules = new ArrayList<>(List.of(required(always)));
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of(), null, rules);
    rules.clear();
    assertThat(definition.isEmpty()).isFalse();
    assertThat(definition.validationsOrEmpty()).hasSize(1);
    assertThat(mapper.readValue(mapper.writeValueAsString(definition), RecordCalculationDefinition.class)).isEqualTo(definition);
    assertThatThrownBy(() -> definition.validations().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThat(mapper.readValue(mapper.writeValueAsString(legacy), RecordCalculationDefinition.class).validations()).isNull();
  }

  @Test
  void rejectsAmbiguousShapesRelationsAndUnboundedLiterals() {
    assertThatThrownBy(() -> new RecordValidationRule("r", RecordValidationRule.Kind.REQUIRED, total, null, null, "1", null, always))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValidationRule("r", RecordValidationRule.Kind.DETAIL_COUNT, null, null, "lines", "1.5", null, always))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValidationRule("r", RecordValidationRule.Kind.RANGE, total, null, null, "10", "1", always))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValidationRule("r", RecordValidationRule.Kind.DETAIL_SUM, total, total, null, null, null, always))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValidationField("parent", "unsafe.column"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValidationCondition(RecordValueConditionOperator.EQUALS, total,
        List.of(new LogicalValue(LogicalValueKind.STRING, "x".repeat(2001))), List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundsExpandedTreesAndTheWholeDefinitionBeforeCompilation() {
    var condition = always;
    for (int i = 0; i < 12; i++) {
      condition = new RecordValidationCondition(RecordValueConditionOperator.AND, null, List.of(), List.of(condition, condition));
    }
    var oversized = condition;
    assertThatThrownBy(() -> required(oversized)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(), null, List.of(required(always), required(always))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(), null, java.util.Collections.nCopies(2001, required(always))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preservesExistingRuleJsonAndRoundTripsOptionalFieldConstraints() throws Exception {
    String old = mapper.writeValueAsString(required(always));
    assertThat(old).doesNotContain("constraints");
    var constraint = new RecordFieldConstraints(1, 3, "^[A-Z]+$", List.of());
    var rule = new RecordValidationRule("field", RecordValidationRule.Kind.FIELD, total, null, null, null, null, always, constraint);
    assertThat(mapper.readValue(mapper.writeValueAsString(rule), RecordValidationRule.class)).isEqualTo(rule);
    assertThatThrownBy(() -> new RecordValidationRule("field", RecordValidationRule.Kind.FIELD, total, null, null, null, null, always))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordValidationRule("required", RecordValidationRule.Kind.REQUIRED, total, null, null, null, null, always, constraint))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void includesConditionalFieldConstraintsInTheWholeDefinitionBudget() {
    var constraints = new RecordFieldConstraints(null, null, null, List.of(new LogicalValue(LogicalValueKind.STRING, "x".repeat(2000))));
    var rules = java.util.stream.IntStream.range(0, 33).mapToObj(i -> new RecordValidationRule("r" + i,
        RecordValidationRule.Kind.FIELD, total, null, null, null, null, always, constraints)).toList();
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(), null, rules)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(),
        Map.of("total", constraints), rules.subList(0, 32))).isInstanceOf(IllegalArgumentException.class);
  }

  private RecordValidationRule required(RecordValidationCondition when) {
    return new RecordValidationRule("require-total", RecordValidationRule.Kind.REQUIRED, total, null, null, null, null, when);
  }

  @Test
  void boundsControlFieldsWithoutReducingTheBusinessFieldCapacity() {
    var constraint = new RecordFieldConstraints(1, null, null, List.of());
    var constraints = java.util.stream.IntStream.range(0, 512).boxed()
        .collect(java.util.stream.Collectors.toMap(i -> "field" + i, i -> constraint));
    assertThat(new RecordCalculationDefinition("1", Map.of(), Map.of(), constraints).constraintsOrEmpty()).hasSize(512);
    constraints.put("control", constraint);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(), constraints)).isInstanceOf(IllegalArgumentException.class);
  }
}
