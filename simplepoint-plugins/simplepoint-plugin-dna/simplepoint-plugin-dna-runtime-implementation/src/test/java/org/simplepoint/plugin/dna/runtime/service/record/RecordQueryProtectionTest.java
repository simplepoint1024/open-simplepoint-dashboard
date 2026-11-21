package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;

class RecordQueryProtectionTest {
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  @Test
  void persistsDestinationsAndOnlyTheirTransitiveConsumersAcrossRemovedAndRenamedScopes() {
    var mapping =
        new RecordReferenceMapping(
            "copy",
            "name",
            "copied",
            RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
            RecordReferenceConversion.IDENTITY,
            RecordReferenceMapping.NullPolicy.CLEAR);
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("lines", "customerId"),
            "customers",
            "app",
            "customersForm",
            "customer-page",
            null,
            null,
            null,
            List.of(mapping));
    var definition =
        new RecordCalculationDefinition(
            "1",
            Map.of("total", "SUM([lines.result])", "constant", "42"),
            Map.of(
                "lines",
                new RecordCalculationScope("detail", SCHEMA, Map.of("result", "LEN([copied])"))),
            null,
            null,
            null,
            null,
            List.of(rule));
    var graph = new LinkedHashMap<String, Set<String>>();
    graph.put("parent.total", Set.of("lines.result"));
    graph.put("lines.result", Set.of("lines.copied"));
    graph.put("parent.constant", Set.of());
    var protectedFields = RecordQueryProtection.derive(Map.of(), "root", definition, graph);
    assertThat(protectedFields).containsOnlyKeys("root", "detail");
    assertThat(protectedFields.get("root")).containsExactly("total");
    assertThat(protectedFields.get("detail")).containsExactly("copied", "result");
    var replacement =
        new RecordCalculationDefinition(
            "1",
            Map.of("next", "SUM([renamed.result])"),
            Map.of("renamed", new RecordCalculationScope("detail", SCHEMA, Map.of())));
    var later =
        RecordQueryProtection.derive(
            protectedFields, "root", replacement, Map.of("parent.next", Set.of("renamed.result")));
    assertThat(later.get("root")).containsExactly("next", "total");
    assertThat(later.get("detail")).isEqualTo(protectedFields.get("detail"));
    assertThat(
            RecordQueryProtection.derive(
                later, "root", new RecordCalculationDefinition("1", Map.of(), Map.of()), Map.of()))
        .isEqualTo(later);
    assertThatThrownBy(() -> later.get("root").clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void legacyPoliciesKeepExactJsonAndCanonicalProtectedIdentitiesIgnoreInsertionOrder()
      throws Exception {
    var mapper = new ObjectMapper();
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of());
    var old = new RecordCalculationPolicy(1, "app", "form", SCHEMA, definition);
    var json = mapper.writeValueAsString(old);
    assertThat(json).doesNotContain("queryProtection");
    assertThat(mapper.readValue(json, RecordCalculationPolicy.class)).isEqualTo(old);
    var first =
        new RecordCalculationPolicy(
            1,
            "app",
            "form",
            SCHEMA,
            definition,
            Map.of("root", new LinkedHashSet<>(List.of("z", "a"))));
    var second =
        new RecordCalculationPolicy(
            1,
            "app",
            "form",
            SCHEMA,
            definition,
            Map.of("root", new LinkedHashSet<>(List.of("a", "z"))));
    assertThat(RecordCalculationStore.policyChecksum(first))
        .isEqualTo(RecordCalculationStore.policyChecksum(second));
    assertThat(mapper.readValue(mapper.writeValueAsString(first), RecordCalculationPolicy.class))
        .isEqualTo(first);
  }

  @Test
  void rejectsUnboundedOrUnresolvableProtectionInsteadOfDroppingOldFields() {
    assertThatThrownBy(() -> RecordQueryProtection.immutable(Map.of("root", Set.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RecordQueryProtection.immutable(Map.of("root", Set.of("scope.field"))))
        .isInstanceOf(IllegalArgumentException.class);
    var tooMany = new LinkedHashMap<String, Set<String>>();
    for (int i = 0; i < 65; i++) {
      tooMany.put("model" + i, Set.of("field"));
    }
    assertThatThrownBy(() -> RecordQueryProtection.immutable(tooMany))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RecordQueryProtection.derive(
                    Map.of("root", Set.of("field")),
                    "root",
                    new RecordCalculationDefinition("1", Map.of(), Map.of()),
                    Map.of("parent.total", Set.of("missing.field"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
