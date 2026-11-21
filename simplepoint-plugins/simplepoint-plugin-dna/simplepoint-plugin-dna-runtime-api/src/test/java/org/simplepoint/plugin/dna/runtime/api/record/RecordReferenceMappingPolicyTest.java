package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordReferenceMappingPolicyTest {
  @Test
  void oldPolicyRetainsOptionalAbsenceWhileNewMappingsAreImmutableAndCanonical() throws Exception {
    var json = new ObjectMapper();
    var legacy =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("parent", "customer"),
            "target",
            "app",
            "form",
            "resource",
            null,
            null,
            "label");
    assertThat(json.writeValueAsString(legacy)).doesNotContain("mappings");
    assertThat(json.readValue(json.writeValueAsString(legacy), RecordReferenceRule.class))
        .isEqualTo(legacy);
    var first = rule("parent", "customer", List.of(mapping("address"), mapping("name")));
    var reversed = rule("parent", "customer", first.mappings().reversed());
    assertThat(json.writeValueAsString(first)).isEqualTo(json.writeValueAsString(reversed));
    assertThat(json.readValue(json.writeValueAsString(first), RecordReferenceRule.class))
        .isEqualTo(first);
    assertThatThrownBy(() -> first.mappings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void providerIdentityIsLocalToEachOwnedRowButCannotReplaceReferencesOrFormulas() {
    var root = rule("parent", "customer", List.of(mapping("address")));
    var detail = rule("lines", "customer", List.of(mapping("address")));
    var details =
        Map.of(
            "lines",
            new RecordCalculationScope(
                "detail-model",
                new RecordSchemaReference(1, "a".repeat(64)),
                Map.<String, String>of()));
    var valid =
        new RecordCalculationDefinition(
            "1", Map.of(), details, null, null, null, null, List.of(root, detail));
    assertThat(valid.referencesOrEmpty()).hasSize(2);
    assertThatThrownBy(
            () ->
                new RecordCalculationDefinition(
                    "1", Map.of("address", "1"), details, null, null, null, null, List.of(root)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RecordCalculationDefinition(
                    "1", Map.of(), Map.of(), null, null, null, null, List.of(detail)))
        .isInstanceOf(IllegalArgumentException.class);
    var duplicate = rule("parent", "supplier", List.of(mapping("address")));
    assertThatThrownBy(
            () ->
                new RecordCalculationDefinition(
                    "1", Map.of(), Map.of(), null, null, null, null, List.of(root, duplicate)))
        .isInstanceOf(IllegalArgumentException.class);
    var overwriteReference = rule("parent", "supplier", List.of(mapping("customer")));
    assertThatThrownBy(
            () ->
                new RecordCalculationDefinition(
                    "1",
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    List.of(root, overwriteReference)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundedMappingsRejectAmbiguousAndSelfReplacingDefinitions() {
    assertThatThrownBy(() -> rule("parent", "customer", List.of(mapping("customer"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> rule("parent", "customer", List.of(mapping("address"), mapping("address"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                rule(
                    "parent",
                    "customer",
                    java.util.stream.IntStream.range(0, 33)
                        .mapToObj(i -> mapping("f" + i))
                        .toList()))
        .isInstanceOf(IllegalArgumentException.class);
    var references =
        java.util.stream.IntStream.range(0, 5)
            .mapToObj(
                index ->
                    rule(
                        "parent",
                        "customer" + index,
                        java.util.stream.IntStream.range(0, 26)
                            .mapToObj(field -> mapping("f" + index + "_" + field))
                            .toList()))
            .toList();
    assertThatThrownBy(
            () ->
                new RecordCalculationDefinition(
                    "1", Map.of(), Map.of(), null, null, null, null, references))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordReferenceRule rule(
      String scope, String source, List<RecordReferenceMapping> mappings) {
    return new RecordReferenceRule(
        "1",
        scope + "-" + source,
        new RecordValidationField(scope, source),
        "target",
        "app",
        "form",
        "resource",
        null,
        null,
        null,
        mappings);
  }

  private static RecordReferenceMapping mapping(String destination) {
    return new RecordReferenceMapping(
        "map-" + destination,
        "target",
        destination,
        RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
        RecordReferenceConversion.IDENTITY,
        RecordReferenceMapping.NullPolicy.CLEAR);
  }
}
