package org.simplepoint.plugin.dna.runtime.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LogicalReferenceDefinitionTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void legacyReferenceJsonRemainsByteCompatible() throws Exception {
    String original =
        "{\"relationId\":\"customer\",\"fieldId\":\"customerId\",\"targetModelId\":\"customers\",\"unique\":false}";
    var reference = mapper.readValue(original, LogicalReferenceDefinition.class);
    assertThat(reference.targetFields()).isEmpty();
    assertThat(mapper.writeValueAsString(reference)).isEqualTo(original);
  }

  @Test
  void dependenciesAreBoundedDistinctCanonicalAndImmutable() throws Exception {
    var name = new LogicalReferenceField("name", LogicalFieldType.STRING);
    var active = new LogicalReferenceField("active", LogicalFieldType.BOOLEAN);
    var reference =
        new LogicalReferenceDefinition(
            "customer", "customerId", "customers", false, List.of(name, active));
    assertThat(reference.targetFields()).containsExactly(active, name);
    assertThat(
            mapper.readValue(
                mapper.writeValueAsString(reference), LogicalReferenceDefinition.class))
        .isEqualTo(reference);
    assertThatThrownBy(() -> reference.targetFields().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                new LogicalReferenceDefinition(
                    "customer",
                    "customerId",
                    "customers",
                    false,
                    List.of(name, new LogicalReferenceField("name", LogicalFieldType.TEXT))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new LogicalReferenceDefinition(
                    "customer",
                    "customerId",
                    "customers",
                    false,
                    IntStream.range(0, 129)
                        .mapToObj(
                            i -> new LogicalReferenceField("field" + i, LogicalFieldType.STRING))
                        .toList()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void evolvingDependenciesDoesNotAuthorizeRetargetingOrChangingUniqueness() {
    var original = new LogicalReferenceDefinition("customer", "customerId", "customers", false);
    assertThat(
            original.sameStorageAs(
                new LogicalReferenceDefinition(
                    "customer",
                    "customerId",
                    "customers",
                    false,
                    List.of(new LogicalReferenceField("name", LogicalFieldType.STRING)))))
        .isTrue();
    assertThat(
            original.sameStorageAs(
                new LogicalReferenceDefinition("customer", "customerId", "other", false)))
        .isFalse();
    assertThat(
            original.sameStorageAs(
                new LogicalReferenceDefinition("customer", "customerId", "customers", true)))
        .isFalse();
  }

  @Test
  void schemaEvolutionMayTightenUniquenessOnTheSameForeignKey() {
    var many = new LogicalReferenceDefinition("customer", "customerId", "customers", false);
    var one = new LogicalReferenceDefinition("customer", "customerId", "customers", true);
    assertThat(many.canEvolveTo(one)).isTrue();
    assertThat(one.canEvolveTo(one)).isTrue();
    assertThat(one.canEvolveTo(many)).isFalse();
    assertThat(many.canEvolveTo(
        new LogicalReferenceDefinition("customer", "customerId", "other", true))).isFalse();
  }
}
