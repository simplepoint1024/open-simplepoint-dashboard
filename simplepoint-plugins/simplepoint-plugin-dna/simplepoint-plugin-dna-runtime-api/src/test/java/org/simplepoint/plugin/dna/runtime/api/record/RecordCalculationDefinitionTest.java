package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecordCalculationDefinitionTest {
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void roundTripsPublicationAndKeepsAbsentDefinitionAbsent() throws Exception {
    var legacy = new RecordWritePolicyPublicationCommand("parent-model", "app", "form", SCHEMA,
        0, 1, "publish", "b".repeat(64), Map.of(), 1000);
    String json = mapper.writeValueAsString(legacy);
    assertThat(json).doesNotContain("calculation").doesNotContain("valueChecks");
    assertThat(mapper.readValue(json, RecordWritePolicyPublicationCommand.class)).isEqualTo(legacy);

    var definition = new RecordCalculationDefinition("1", Map.of("total", "SUM([expenses.amount])"),
        Map.of("expenses", detail("child-model", Map.of("amount", "ROUND([price]*[quantity],2)"))));
    var command = publication(definition);
    assertThat(mapper.readValue(mapper.writeValueAsString(command), RecordWritePolicyPublicationCommand.class))
        .isEqualTo(command);
    var remove = publication(new RecordCalculationDefinition("1", Map.of(), Map.of()));
    assertThat(mapper.writeValueAsString(remove)).contains("\"calculation\"");
    assertThat(mapper.readValue(mapper.writeValueAsString(remove), RecordWritePolicyPublicationCommand.class))
        .isEqualTo(remove);
  }

  @Test
  void copiesInputsWithoutRewritingExpressionText() {
    Map<String, String> expressions = new LinkedHashMap<>();
    expressions.put("amount", "=CONCAT(\"中文・日本語😀\",[名称])");
    var child = detail("child-model", expressions);
    Map<String, RecordCalculationScope> details = new LinkedHashMap<>();
    details.put("expenses", child);
    var definition = new RecordCalculationDefinition("1", Map.of(), details);
    expressions.clear();
    details.clear();
    assertThat(definition.details().get("expenses").formulas().get("amount"))
        .isEqualTo("=CONCAT(\"中文・日本語😀\",[名称])");
    assertThatThrownBy(() -> definition.details().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> child.formulas().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsAmbiguousIdentitiesAndParentAliasing() {
    var child = detail("child-model", Map.of());
    assertThatThrownBy(() -> new RecordCalculationDefinition("2", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of("invalid.id", "1"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of("parent", child)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of("a", child, "b", child)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> publication(new RecordCalculationDefinition("1", Map.of(),
        Map.of("a", detail("parent-model", Map.of()))))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundsExpressionsAndAggregateWorkAcrossScopes() {
    assertThatThrownBy(() -> detail("child", Map.of("f", " "))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> detail("child", Map.of("f", "x".repeat(8193))))
        .isInstanceOf(IllegalArgumentException.class);
    var longExpressions = IntStream.range(0, 9).boxed().collect(
        Collectors.toMap(i -> "f" + i, i -> "x".repeat(8192)));
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", longExpressions, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    var manyExpressions = IntStream.range(0, 256).boxed().collect(Collectors.toMap(i -> "f" + i, i -> "1"));
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", manyExpressions,
        Map.of("detail", detail("child", Map.of("extra", "1"))))).isInstanceOf(IllegalArgumentException.class);
    var manyScopes = IntStream.range(0, 6).boxed().collect(
        Collectors.toMap(i -> "d" + i, i -> detail("model" + i, Map.of())));
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), manyScopes))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preservesAbsentRulesAndRoundTripsStableConstraintScopes() throws Exception {
    var legacy = new RecordCalculationDefinition("1", Map.of("total", "1"), Map.of("lines", detail("child", Map.of())));
    assertThat(mapper.writeValueAsString(legacy)).doesNotContain("constraints", "empty");
    var rule = new RecordFieldConstraints(1, 20, "^[a-z]+$", java.util.List.of());
    Map<String, RecordFieldConstraints> rules = new LinkedHashMap<>(Map.of("name", rule));
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of("lines",
        new RecordCalculationScope("child", SCHEMA, Map.of(), rules)), rules);
    rules.clear();
    assertThat(definition.isEmpty()).isFalse();
    assertThat(definition.details().get("lines").constraintsOrEmpty()).containsEntry("name", rule);
    var json = mapper.writeValueAsString(publication(definition));
    assertThat(mapper.readValue(json, RecordWritePolicyPublicationCommand.class).calculation()).isEqualTo(definition);
    assertThatThrownBy(() -> definition.constraints().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new RecordFieldConstraints(2, 1, null, java.util.List.of())).isInstanceOf(IllegalArgumentException.class);
    var choices = java.util.Collections.nCopies(200, new org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue(
        org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.STRING, "x".repeat(2000)));
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(), Map.of("name",
        new RecordFieldConstraints(null, null, null, choices)))).isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordCalculationScope detail(String model, Map<String, String> formulas) {
    return new RecordCalculationScope(model, SCHEMA, formulas);
  }

  private static RecordWritePolicyPublicationCommand publication(RecordCalculationDefinition definition) {
    return new RecordWritePolicyPublicationCommand("parent-model", "app", "form", SCHEMA,
        0, 1, "publish", "b".repeat(64), Map.of(), 1000, Map.of(), definition);
  }
}
