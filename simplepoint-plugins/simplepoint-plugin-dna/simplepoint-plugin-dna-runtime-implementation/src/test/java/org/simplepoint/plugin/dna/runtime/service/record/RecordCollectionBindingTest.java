package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.CollectionModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RecordCollectionBindingTest {
  @Test
  void resolvesStableEndpointRolesAndSupportsSelfRelations() {
    var binding = binding("projects", "members", "edges", "source_id", "target_id");
    assertThat(binding.sourceColumn()).isEqualTo("source_id");
    assertThat(binding.targetColumn()).isEqualTo("target_id");
    assertThat(binding("projects", "projects", "edges", "source_id", "target_id").target())
        .isEqualTo(binding.source());
  }

  @Test
  void rejectsReversedModelsDifferentStoresAndForeignFormOwnership() {
    var binding = binding("projects", "members", "edges", "source_id", "target_id");
    assertThatThrownBy(
            () -> new RecordCollectionBinding(binding.edge(), binding.target(), binding.source()))
        .isInstanceOf(DnaRuntimeException.class);
    var target = binding.target();
    var otherStore =
        new RuntimeRecordModel(
            target.physicalModelId(),
            target.appId(),
            target.formId(),
            1,
            target.activeSchema(),
            new PhysicalSchemaTarget(
                target.physicalModelId(),
                new RuntimeMetadataTarget("other", "db", "public", "members")));
    assertThatThrownBy(
            () -> new RecordCollectionBinding(binding.edge(), binding.source(), otherStore))
        .isInstanceOf(DnaRuntimeException.class);
    var edge = binding.edge();
    var foreign =
        new RuntimeRecordModel(
            edge.physicalModelId(),
            "foreign",
            edge.formId(),
            1,
            edge.activeSchema(),
            edge.target());
    assertThatThrownBy(
            () -> new RecordCollectionBinding(foreign, binding.source(), binding.target()))
        .isInstanceOf(DnaRuntimeException.class);
    var reusedRootIdentity =
        new RuntimeRecordModel(
            edge.physicalModelId(),
            edge.appId(),
            binding.source().formId(),
            1,
            edge.activeSchema(),
            edge.target());
    assertThatThrownBy(
            () ->
                new RecordCollectionBinding(reusedRootIdentity, binding.source(), binding.target()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  static RecordCollectionBinding binding(
      String sourceId,
      String targetId,
      String edgeTable,
      String sourceColumn,
      String targetColumn) {
    var root = new PhysicalSchemaDefinition("v1", List.of(), List.of(), null, true);
    var source = model(sourceId, root, target(sourceId));
    var target = model(targetId, root, target(targetId));
    var schema =
        new PhysicalSchemaDefinition(
            "v1",
            List.of(field("source", sourceColumn), field("target", targetColumn)),
            List.of(new LogicalIndexDefinition("pair", List.of(sourceColumn, targetColumn), true)),
            null,
            true,
            List.of(
                new LogicalReferenceDefinition("left", "source", sourceId, false),
                new LogicalReferenceDefinition("right", "target", targetId, false)),
            new CollectionModelRelation("members", "left", "right"));
    Map<String, RuntimeMetadataTarget> targets = new java.util.HashMap<>();
    targets.put(sourceId, source.target().metadataTarget());
    targets.put(targetId, target.target().metadataTarget());
    var edge =
        new RuntimeRecordModel(
            "membership",
            "app",
            schema.collection().modelFormId("form"),
            1,
            schema,
            new PhysicalSchemaTarget("membership", target(edgeTable), targets));
    return new RecordCollectionBinding(edge, source, target);
  }

  private static RuntimeRecordModel model(
      String id, PhysicalSchemaDefinition schema, RuntimeMetadataTarget location) {
    return new RuntimeRecordModel(
        id, "app", "form", 1, schema, new PhysicalSchemaTarget(id, location));
  }

  private static RuntimeMetadataTarget target(String table) {
    return new RuntimeMetadataTarget("source", "db", "public", table);
  }

  private static LogicalFieldDefinition field(String id, String code) {
    return new LogicalFieldDefinition(
        id, code, id, LogicalFieldType.UUID, true, null, null, null, null);
  }
}
