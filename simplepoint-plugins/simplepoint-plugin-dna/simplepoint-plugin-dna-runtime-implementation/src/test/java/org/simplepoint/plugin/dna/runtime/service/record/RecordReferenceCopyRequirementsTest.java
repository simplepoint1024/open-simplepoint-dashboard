package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RecordReferenceCopyRequirementsTest {
  private final RuntimeRecordModel parent = model("target", false);
  private final RuntimeRecordModel child = model("lines", true);
  private final RecordView root = row();
  private final RecordView first = row();
  private final RecordView second = row();

  @Test
  void copyingAnAlreadyCopiedValueRetainsOriginalPermissionFootprintAfterMappingRemoval() {
    var prior = RecordReferenceCopyStoreTest.origin();
    var origins = List.of(new RecordReferenceCopySnapshot(null, root.id(), 0, List.of(prior)));
    var captured =
        RecordReferenceCopyRequirements.capture(
            parent,
            null,
            new OwnedRecordView(root, Map.of()),
            origins,
            "target-page",
            Set.of("address"));
    assertThat(captured.get("address")).hasSize(2).containsAll(prior.requirements());
    assertThat(
            captured.get("address").stream()
                .filter(value -> value.modelId().equals("target"))
                .findFirst()
                .orElseThrow()
                .fieldIds())
        .containsExactly("address");
    assertThat(prior.requirements()).hasSize(1);
    assertThatThrownBy(() -> captured.clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void calculatedCopiesFreezeEveryOwnedInputAndCopiedAncestorWithExactRootIdentity() {
    var prior = RecordReferenceCopyStoreTest.origin();
    var origins = List.of(new RecordReferenceCopySnapshot("lines", first.id(), 0, List.of(prior)));
    var captured =
        RecordReferenceCopyRequirements.capture(
            parent,
            binding(),
            new OwnedRecordView(root, Map.of("lines", List.of(first, second))),
            origins,
            "target-page",
            Set.of("total"));
    var requirements = captured.get("total");
    assertThat(requirements).hasSize(4).containsAll(prior.requirements());
    assertThat(
            requirements.stream().filter(value -> "lines".equals(value.detailModelId())).toList())
        .hasSize(2)
        .allSatisfy(
            value -> {
              assertThat(value.recordId()).isEqualTo(root.id());
              assertThat(value.resourceCode()).isEqualTo("target-page");
              assertThat(value.fieldIds()).containsExactly("address", "total");
            });
    assertThat(
            requirements.stream()
                .map(value -> value.detailRecordId())
                .filter(java.util.Objects::nonNull)
                .toList())
        .containsExactlyInAnyOrder(first.id(), second.id());
  }

  @Test
  void emptyCollectionRetainsHistoricalFieldRequirementsWithoutInventingChildRecords() {
    var requirements =
        RecordReferenceCopyRequirements.capture(
                parent,
                binding(),
                new OwnedRecordView(root, Map.of("lines", List.of())),
                List.of(),
                "target-page",
                Set.of("total"))
            .get("total");
    assertThat(requirements).hasSize(2);
    var detail =
        requirements.stream()
            .filter(value -> value.detailModelId() != null)
            .findFirst()
            .orElseThrow();
    assertThat(detail.detailRecordId()).isNull();
    assertThat(detail.fieldIds()).containsExactly("address", "total");
    assertThat(detail.recordId()).isEqualTo(root.id());
  }

  @Test
  void missingCollectionsMaskedInputsOrStaleCopiedRowsCannotProducePartialProofs() {
    assertThatThrownBy(
            () ->
                RecordReferenceCopyRequirements.capture(
                    parent,
                    binding(),
                    new OwnedRecordView(root, Map.of()),
                    List.of(),
                    "target-page",
                    Set.of("total")))
        .isInstanceOf(DnaRuntimeException.class);
    for (var fields :
        List.of(
            Map.<String, Object>of("total", "visible"),
            Map.<String, Object>of("total", "visible", "address", "***"))) {
      var hidden = new RecordView(first.id(), 0, fields);
      assertThatThrownBy(
              () ->
                  RecordReferenceCopyRequirements.capture(
                      parent,
                      binding(),
                      new OwnedRecordView(root, Map.of("lines", List.of(hidden))),
                      List.of(),
                      "target-page",
                      Set.of("total")))
          .isInstanceOf(DnaRuntimeException.class);
    }
    var stale =
        new RecordReferenceCopySnapshot(
            "lines", first.id(), 1, List.of(RecordReferenceCopyStoreTest.origin()));
    assertThatThrownBy(
            () ->
                RecordReferenceCopyRequirements.capture(
                    parent,
                    binding(),
                    new OwnedRecordView(root, Map.of("lines", List.of(first))),
                    List.of(stale),
                    "target-page",
                    Set.of("total")))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void derivedProofsAreRowLocalWhileParentAggregatesIncludeAllOwnedInputs() {
    var prior = RecordReferenceCopyStoreTest.origin();
    var values = new OwnedRecordView(root, Map.of("lines", List.of(first, second)));
    var origins = List.of(new RecordReferenceCopySnapshot("lines", first.id(), 0, List.of(prior)));
    var captured =
        RecordDerivedCopyRequirements.capture(
            installedBinding(binding().dependencies()), values, origins);
    assertThat(captured).hasSize(2);
    var parentProof =
        captured.stream().filter(row -> row.detailModelId() == null).findFirst().orElseThrow();
    assertThat(parentProof.derivedOrigins())
        .singleElement()
        .satisfies(
            origin -> {
              assertThat(origin.destinationFieldId()).isEqualTo("total");
              assertThat(origin.requirements()).containsExactlyElementsOf(prior.requirements());
              assertThat(origin.localRequirements())
                  .hasSize(2)
                  .allSatisfy(
                      requirement -> {
                        assertThat(requirement.modelId()).isEqualTo("lines");
                        assertThat(requirement.fieldIds()).containsExactly("address", "total");
                      });
            });
    var childProof =
        captured.stream().filter(row -> row.detailModelId() != null).findFirst().orElseThrow();
    assertThat(childProof.sourceRecordId()).isEqualTo(first.id());
    assertThat(childProof.derivedOrigins())
        .singleElement()
        .satisfies(
            origin ->
                assertThat(origin.localRequirements())
                    .containsExactly(
                        new RecordAggregateReadRequirement(
                            "lines", first.id(), List.of("address"))));
    assertThat(captured.stream().map(RecordReferenceCopySnapshot::sourceRecordId))
        .doesNotContain(second.id());
    // Removing a formula keeps its old proof. Actual recalculation to a constant clears that output
    // only.
    assertThat(RecordDerivedCopyRequirements.capture(installedBinding(Map.of()), values, captured))
        .containsExactlyInAnyOrderElementsOf(captured);
    var recalculated =
        RecordDerivedCopyRequirements.capture(
            installedBinding(Map.of("parent.total", Set.of())), values, captured);
    assertThat(recalculated).containsExactly(childProof);
    // A new formula replacing a former copy removes the old provider, but keeps retained consumers.
    var replaced =
        RecordDerivedCopyRequirements.capture(
            installedBinding(Map.of("detail.address", Set.of())), values, captured);
    assertThat(
            replaced.stream()
                .filter(row -> row.detailModelId() != null)
                .findFirst()
                .orElseThrow()
                .origins())
        .isEmpty();
  }

  @Test
  void recalculationFromRetainedDerivedInputPreservesItsOriginalLocalAndExternalDependencies() {
    var previous =
        new RecordDerivedCopyOrigin(
            1,
            "total",
            3,
            "a".repeat(64),
            List.of(new RecordAggregateReadRequirement("lines", first.id(), List.of("address"))),
            RecordReferenceCopyStoreTest.origin().requirements());
    var input = new RecordReferenceCopySnapshot(null, root.id(), 0, List.of(), List.of(previous));
    var captured =
        RecordDerivedCopyRequirements.capture(
            installedBinding(Map.of("parent.address", Set.of("parent.total"))),
            new OwnedRecordView(root, Map.of("lines", List.of(first, second))),
            List.of(input));
    var output =
        captured.getFirst().derivedOrigins().stream()
            .filter(origin -> origin.destinationFieldId().equals("address"))
            .findFirst()
            .orElseThrow();
    assertThat(output.requirements()).isEqualTo(previous.requirements());
    assertThat(output.localRequirements())
        .containsExactlyInAnyOrder(
            new RecordAggregateReadRequirement("lines", first.id(), List.of("address")),
            new RecordAggregateReadRequirement("target", root.id(), List.of("total")));
    assertThat(output.calculationRevision()).isEqualTo(9);
  }

  private RecordCalculationWriteSupport.Binding installedBinding(Map<String, Set<String>> graph) {
    var definition =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition(
            "1", Map.of(), Map.of());
    var policy =
        new RecordCalculationPolicy(
            1,
            "app",
            "form",
            new org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference(
                1, "a".repeat(64)),
            definition);
    return new RecordCalculationWriteSupport.Binding(
        new RecordCalculationStore.Installed(9, policy),
        parent,
        Map.of("detail", child),
        graph,
        null);
  }

  private RecordCalculationWriteSupport.Binding binding() {
    return new RecordCalculationWriteSupport.Binding(
        null,
        parent,
        Map.of("detail", child),
        Map.of("parent.total", Set.of("detail.total"), "detail.total", Set.of("detail.address")),
        null);
  }

  private static RecordView row() {
    return new RecordView(
        UUID.randomUUID().toString(), 0, Map.of("address", "retained", "total", "computed"));
  }

  private static RuntimeRecordModel model(String id, boolean owned) {
    var fields = new java.util.ArrayList<LogicalFieldDefinition>();
    fields.add(
        new LogicalFieldDefinition(
            "address", "address", "Address", LogicalFieldType.STRING, true, 255, null, null, null));
    fields.add(
        new LogicalFieldDefinition(
            "total", "total", "Total", LogicalFieldType.STRING, true, 255, null, null, null));
    if (owned) {
      fields.add(
          new LogicalFieldDefinition(
              "owner",
              "parent_record_id",
              "Owner",
              LogicalFieldType.UUID,
              false,
              null,
              null,
              null,
              null));
    }
    var schema =
        new PhysicalSchemaDefinition(
            org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion.CURRENT,
            fields,
            List.of(),
            owned ? new OwnedModelRelation("target", "owner") : null);
    return new RuntimeRecordModel(
        id,
        "app",
        "form",
        1,
        schema,
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("source", "catalog", "schema", id)));
  }
}
