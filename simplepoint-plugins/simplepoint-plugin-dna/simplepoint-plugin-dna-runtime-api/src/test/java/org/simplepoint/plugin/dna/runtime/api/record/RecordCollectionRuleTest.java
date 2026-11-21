package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordCollectionRuleTest {
  @Test
  void preservesLegacyBytesAndCanonicalizesIndependentCollectionDeclarations() throws Exception {
    var mapper = new ObjectMapper();
    var legacy = new RecordCalculationDefinition("1", Map.of(), Map.of());
    assertThat(mapper.writeValueAsString(legacy))
        .isEqualTo("{\"languageVersion\":\"1\",\"formulas\":{},\"details\":{}}");
    var input = new ArrayList<>(List.of(rule("z", "edge-z"), rule("a", "edge-a")));
    var definition = definition(input, null);
    input.clear();
    assertThat(definition.collectionsOrEmpty())
        .extracting(RecordCollectionRule::relationId)
        .containsExactly("a", "z");
    assertThat(definition.isEmpty()).isFalse();
    assertThat(
            mapper.readValue(
                mapper.writeValueAsString(definition), RecordCalculationDefinition.class))
        .isEqualTo(definition);
    assertThatThrownBy(() -> definition.collections().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsCollidingRelationsModelsAndTheSharedCollectionBudget() {
    for (var rules :
        List.of(
            List.of(rule("a", "edge"), rule("b", "edge")),
            List.of(rule("a", "edge-a"), rule("a", "edge-b")),
            java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> rule("r" + i, "e" + i))
                .toList())) {
      assertThatThrownBy(() -> definition(rules, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var scalar =
        new RecordReferenceRule(
            "1",
            "a",
            new RecordValidationField("parent", "customer"),
            "target",
            "app",
            "form",
            "page",
            null,
            null);
    assertThatThrownBy(() -> definition(List.of(rule("a", "edge")), List.of(scalar)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void forbidsDetailConditionsAndSeparatesCollectionPermissionFromOrdinaryFields() {
    var condition =
        new RecordValidationCondition(
            RecordValueConditionOperator.IS_EMPTY,
            new RecordValidationField("detail", "field"),
            List.of(),
            List.of());
    assertThatThrownBy(
            () ->
                new RecordCollectionRule(
                    "1", "members", "edge", schema(), "target", "app", "form", "page", condition,
                    null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(RecordPermissionModel.collectionField("members"))
        .isNotEqualTo(RecordPermissionModel.field("members"));
    assertThatThrownBy(() -> RecordCalculationDefinition.identity("collection:members"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordCalculationDefinition definition(
      List<RecordCollectionRule> rules, List<RecordReferenceRule> references) {
    return new RecordCalculationDefinition(
        "1", Map.of(), Map.of(), null, null, null, null, references, rules);
  }

  private static RecordCollectionRule rule(String relation, String edge) {
    return new RecordCollectionRule(
        "1", relation, edge, schema(), "target", "app", "form", "page", null, null, null);
  }

  private static RecordSchemaReference schema() {
    return new RecordSchemaReference(1, "a".repeat(64));
  }
}
