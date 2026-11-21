package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

class RecordCollectionPublicationTest {
  @Test
  void validatesDeclaredDependenciesOnBothEndpointsUsingStableFieldIdsAfterRename() {
    var binding = binding(LogicalFieldType.STRING);
    var rule = rule(binding);
    assertThat(
            RecordCollectionPublication.validate(
                rule, binding.source(), binding.edge(), binding.target()))
        .isEqualTo(binding);
    assertThat(binding.edge().activeSchema().fields()).hasSize(2);
    assertThat(binding.edge().activeSchema().references())
        .allSatisfy(
            reference ->
                assertThat(reference.targetFields())
                    .containsExactly(new LogicalReferenceField("status", LogicalFieldType.STRING)));
  }

  @Test
  void rejectsRetypedDependenciesAndLabelTypesWithoutReinterpretingTheDeclaration() {
    var changed = binding(LogicalFieldType.BOOLEAN);
    assertThatThrownBy(
            () ->
                RecordCollectionPublication.validate(
                    rule(changed), changed.source(), changed.edge(), changed.target()))
        .isInstanceOf(DnaRuntimeException.class);
    var binding = binding(LogicalFieldType.STRING);
    var rule = rule(binding);
    var foreign =
        new RecordCollectionRule(
            "1",
            rule.relationId(),
            rule.edgeModelId(),
            rule.edgeSchema(),
            rule.targetModelId(),
            "foreign-app",
            rule.targetFormId(),
            rule.targetResourceCode(),
            rule.eligibility(),
            rule.mutableWhen(),
            rule.snapshotLabelFieldId());
    assertThatThrownBy(
            () ->
                RecordCollectionPublication.validate(
                    foreign, binding.source(), binding.edge(), binding.target()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  static RecordCollectionRule rule(RecordCollectionBinding binding) {
    return new RecordCollectionRule(
        "1",
        binding.relationId(),
        binding.edge().physicalModelId(),
        new RecordSchemaReference(1, "a".repeat(64)),
        binding.target().physicalModelId(),
        binding.target().appId(),
        binding.target().formId(),
        "member-page",
        condition("ACTIVE"),
        condition("DRAFT"),
        "status");
  }

  private static RecordValidationCondition condition(String expected) {
    return new RecordValidationCondition(
        RecordValueConditionOperator.EQUALS,
        new RecordValidationField("parent", "status"),
        List.of(new LogicalValue(LogicalValueKind.STRING, expected)),
        List.of());
  }

  static RecordCollectionBinding binding(LogicalFieldType type) {
    var base =
        RecordCollectionBindingTest.binding(
            "projects", "members", "edges", "source_id", "target_id");
    var fields =
        List.of(
            new LogicalFieldDefinition(
                "status",
                "renamed_status",
                "Status",
                type,
                true,
                type == LogicalFieldType.STRING ? 32 : null,
                null,
                null,
                null));
    var root = new PhysicalSchemaDefinition("v1", fields, List.of(), null, true);
    var original = base.edge().activeSchema();
    var references =
        original.references().stream()
            .map(
                ref ->
                    new LogicalReferenceDefinition(
                        ref.relationId(),
                        ref.fieldId(),
                        ref.targetModelId(),
                        false,
                        List.of(new LogicalReferenceField("status", LogicalFieldType.STRING))))
            .toList();
    var edge =
        new PhysicalSchemaDefinition(
            original.contractVersion(),
            original.fields(),
            original.indexes(),
            null,
            true,
            references,
            original.collection());
    return new RecordCollectionBinding(
        withSchema(base.edge(), edge),
        withSchema(base.source(), root),
        withSchema(base.target(), root));
  }

  private static RuntimeRecordModel withSchema(
      RuntimeRecordModel model, PhysicalSchemaDefinition schema) {
    return new RuntimeRecordModel(
        model.physicalModelId(), model.appId(), model.formId(), 1, schema, model.target());
  }
}
