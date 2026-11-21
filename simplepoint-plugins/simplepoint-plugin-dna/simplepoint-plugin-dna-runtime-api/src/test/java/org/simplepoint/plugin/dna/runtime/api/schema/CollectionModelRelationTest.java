package org.simplepoint.plugin.dna.runtime.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectionModelRelationTest {
  private final CollectionModelRelation relation =
      new CollectionModelRelation("members", "source", "target");
  private final List<LogicalFieldDefinition> fields = List.of(field("left"), field("right"));
  private final List<LogicalReferenceDefinition> references =
      List.of(
          new LogicalReferenceDefinition("source", "left", "projects", false),
          new LogicalReferenceDefinition("target", "right", "employees", false));
  private final List<LogicalIndexDefinition> indexes =
      List.of(new LogicalIndexDefinition("edge_pair", List.of("left", "right"), true));

  @Test
  void derivesSeparateRegistryIdentityFromRootAndRelationWithoutLabelsOrEndpoints() {
    assertThat(relation.modelFormId("form")).isEqualTo("45e88e9c-127b-38a4-8a1a-8bb20dccb088");
    assertThat(relation.modelFormId("form")).isNotEqualTo(relation.modelFormId("another-form"));
    assertThat(new CollectionModelRelation("another", "source", "target").modelFormId("form"))
        .isNotEqualTo(relation.modelFormId("form"));
    assertThat(new CollectionModelRelation("members", "left", "right").modelFormId("form"))
        .isEqualTo(relation.modelFormId("form"));
    assertThatThrownBy(() -> relation.modelFormId(" form "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> relation.modelFormId("a".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectionModelRelation("_invalid", "source", "target"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void collectionMetadataRoundTripsWithoutChangingLegacySerialization() throws Exception {
    var mapper = new ObjectMapper();
    var legacy = new PhysicalSchemaDefinition("v1", fields, indexes, null, true, references);
    var json = mapper.writeValueAsString(legacy);
    assertThat(json).doesNotContain("collection");
    assertThat(mapper.writeValueAsString(mapper.readValue(json, PhysicalSchemaDefinition.class)))
        .isEqualTo(json);
    var collection =
        new PhysicalSchemaDefinition("v1", fields, indexes, null, true, references, relation);
    assertThat(
            mapper.readValue(mapper.writeValueAsString(collection), PhysicalSchemaDefinition.class))
        .isEqualTo(collection);
    assertThat(collection.references()).hasSize(2);
  }

  @Test
  void rejectsAmbiguousEndpointsBusinessColumnsAndMissingTenantIdentity() {
    assertThatThrownBy(() -> new CollectionModelRelation("members", "source", "source"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PhysicalSchemaDefinition(
                    "v1", fields, indexes, null, null, references, relation))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PhysicalSchemaDefinition(
                    "v1",
                    List.of(field("left"), field("right"), field("business")),
                    indexes,
                    null,
                    true,
                    references,
                    relation))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PhysicalSchemaDefinition(
                    "v1",
                    fields,
                    indexes,
                    null,
                    true,
                    references,
                    new CollectionModelRelation("members", "source", "unknown")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresPairUniquenessAndAllowsIndependentEndpointIndexes() {
    assertThatThrownBy(
            () ->
                new PhysicalSchemaDefinition(
                    "v1", fields, List.of(), null, true, references, relation))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PhysicalSchemaDefinition(
                    "v1",
                    fields,
                    List.of(
                        indexes.getFirst(),
                        new LogicalIndexDefinition("unique_source", List.of("left"), true)),
                    null,
                    true,
                    references,
                    relation))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new PhysicalSchemaDefinition(
                    "v1",
                    fields,
                    List.of(
                        indexes.getFirst(),
                        new LogicalIndexDefinition("target_members", List.of("right"), false)),
                    null,
                    true,
                    references,
                    relation)
                .collection())
        .isEqualTo(relation);
  }

  @Test
  void retainedEdgesRequireNullableLinksWithoutDefaultsOrScalarUniqueness() {
    var mandatory =
        new LogicalFieldDefinition(
            "left", "left", "Left", LogicalFieldType.UUID, false, null, null, null, null);
    assertThatThrownBy(
            () ->
                new PhysicalSchemaDefinition(
                    "v1",
                    List.of(mandatory, field("right")),
                    indexes,
                    null,
                    true,
                    references,
                    relation))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PhysicalSchemaDefinition(
                    "v1",
                    fields,
                    indexes,
                    null,
                    true,
                    List.of(
                        new LogicalReferenceDefinition("source", "left", "projects", true),
                        references.getLast()),
                    relation))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static LogicalFieldDefinition field(String id) {
    return new LogicalFieldDefinition(
        id, id, id, LogicalFieldType.UUID, true, null, null, null, null);
  }
}
