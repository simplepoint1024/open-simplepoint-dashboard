package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordReferenceSnapshotsTest {
  private static final String TARGET_ID = UUID.randomUUID().toString();
  private final RecordSqlCompiler compiler =
      new RecordSqlCompiler(new StandardLogicalValueConverter());

  @Test
  void fortyDetailRowsSharingOneTargetUseOneReadAndRetainEachSourceIdentity() {
    var f = new Fixture(40, TARGET_ID, true);
    var reads = new AtomicInteger();
    var captured =
        RecordReferenceSnapshots.capture(
            f.values,
            f.prepared,
            f.plan,
            compiler,
            query -> {
              reads.incrementAndGet();
              assertThat(query.parameters()).contains(TARGET_ID);
              return result(List.of(List.of(TARGET_ID, 7L, "旧名称-日本語")));
            });
    assertThat(reads).hasValue(1);
    assertThat(captured.referenceSnapshots()).hasSize(40);
    assertThat(captured.referenceSnapshots())
        .allSatisfy(
            label -> {
              assertThat(label.sourceModelId()).isEqualTo("detail");
              assertThat(label.targetVersion()).isEqualTo(7);
              assertThat(label.label()).isEqualTo("旧名称-日本語");
            });
    assertThat(
            captured.referenceSnapshots().stream()
                .map(RecordReferenceSnapshot::sourceRecordId)
                .distinct())
        .hasSize(40);
  }

  @Test
  void maskedSourceAndUnavailableTargetNeverTriggerLabelReads() {
    for (var f : List.of(new Fixture(1, "***", true), new Fixture(1, TARGET_ID, false))) {
      var captured =
          RecordReferenceSnapshots.capture(
              f.values,
              f.prepared,
              f.plan,
              compiler,
              query -> {
                throw new AssertionError("Unauthorized target read");
              });
      assertThat(captured.referenceSnapshots()).isEmpty();
    }
  }

  @Test
  void missingLockCoverageFailsBeforeReadingAnyLabel() {
    var f = new Fixture(1, TARGET_ID, true);
    var noLocks = new RecordWritePreparation(f.prepared.statements(), f.prepared.snapshot());
    assertThatThrownBy(
            () ->
                RecordReferenceSnapshots.capture(
                    f.values,
                    noLocks,
                    f.plan,
                    compiler,
                    query -> {
                      throw new AssertionError("Uncovered target read");
                    }))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void historicalDetailColumnUsesStableIdentityInsteadOfCurrentStorageCode() {
    var f = new Fixture(1, TARGET_ID, true);
    var current = f.plan.sources().get("detail");
    var oldSchema =
        new PhysicalSchemaDefinition(
            "v1",
            List.of(
                new LogicalFieldDefinition(
                    "product",
                    "old_product_id",
                    "Product",
                    LogicalFieldType.UUID,
                    true,
                    null,
                    null,
                    null,
                    null)),
            List.of(),
            null,
            true,
            current.activeSchema().references());
    var historical =
        new RuntimeRecordModel(
            current.physicalModelId(),
            current.appId(),
            current.formId(),
            2,
            current.activeSchema(),
            1,
            oldSchema,
            current.target());
    var parts =
        List.of(
            f.prepared.snapshot().parts().getFirst(),
            new RecordSnapshotPlan.Part(historical, access(), null, false));
    var snapshot =
        new RecordSnapshotPlan(
            f.values.parent().id(), 1, parts, f.prepared.snapshot().validation());
    var prepared =
        new RecordWritePreparation(f.prepared.statements(), snapshot, 1, f.prepared.references());
    var oldRows =
        f.values.details().get("detail").stream()
            .map(
                row ->
                    new RecordView(
                        row.id(),
                        row.version(),
                        Map.<String, Object>of("old_product_id", TARGET_ID)))
            .toList();
    var values = new OwnedRecordView(f.values.parent(), Map.of("detail", oldRows));
    var captured =
        RecordReferenceSnapshots.capture(
            values,
            prepared,
            f.plan,
            compiler,
            query -> result(List.of(List.of(TARGET_ID, 1L, "Original"))));
    assertThat(captured.referenceSnapshots()).hasSize(1);
    assertThat(captured.referenceSnapshots().getFirst().sourceFieldId()).isEqualTo("product");
  }

  @Test
  void omittedHistoricalFieldAndCollectionNeverTriggerTargetReads() {
    var f = new Fixture(1, TARGET_ID, true);
    var current = f.plan.sources().get("detail");
    var empty = new PhysicalSchemaDefinition("v1", List.of(), List.of());
    var historical =
        new RuntimeRecordModel(
            current.physicalModelId(),
            current.appId(),
            current.formId(),
            2,
            current.activeSchema(),
            1,
            empty,
            current.target());
    for (var parts :
        List.of(
            List.of(f.prepared.snapshot().parts().getFirst()),
            List.of(
                f.prepared.snapshot().parts().getFirst(),
                new RecordSnapshotPlan.Part(historical, access(), null, false)))) {
      var snapshot =
          new RecordSnapshotPlan(
              f.values.parent().id(), 1, parts, f.prepared.snapshot().validation());
      var prepared =
          new RecordWritePreparation(f.prepared.statements(), snapshot, 1, f.prepared.references());
      assertThat(
              RecordReferenceSnapshots.capture(
                      f.values,
                      prepared,
                      f.plan,
                      compiler,
                      query -> {
                        throw new AssertionError("Unrequested historical field read");
                      })
                  .referenceSnapshots())
          .isEmpty();
    }
  }

  @Test
  void labelMustBeAnExplicitCompatibleImmutableTargetDependency() {
    var f = new Fixture(1, TARGET_ID, true);
    var binding = f.plan.bindings().get("product");
    var target = binding.target().model();
    for (var dependencies :
        List.of(
            List.<LogicalReferenceField>of(),
            List.of(new LogicalReferenceField("name", LogicalFieldType.BOOLEAN)))) {
      var reference =
          new LogicalReferenceDefinition("product", "product", "target", false, dependencies);
      assertThatThrownBy(
              () -> RecordReferenceRules.snapshotLabel(target, reference, binding.rule()))
          .isInstanceOf(DnaRuntimeException.class);
    }
    var valid =
        new LogicalReferenceDefinition(
            "product",
            "product",
            "target",
            false,
            List.of(new LogicalReferenceField("name", LogicalFieldType.STRING)));
    assertThat(RecordReferenceRules.snapshotLabel(target, valid, binding.rule()).fieldId())
        .isEqualTo("name");
  }

  @Test
  void rowPolicyAbsenceDoesNotCaptureLabelsAndUnexpectedIdentitiesFailClosed() {
    var f = new Fixture(1, TARGET_ID, true);
    assertThat(
            RecordReferenceSnapshots.capture(
                    f.values, f.prepared, f.plan, compiler, query -> result(List.of()))
                .referenceSnapshots())
        .isEmpty();
    assertThatThrownBy(
            () ->
                RecordReferenceSnapshots.capture(
                    f.values,
                    f.prepared,
                    f.plan,
                    compiler,
                    query ->
                        result(List.of(List.of(UUID.randomUUID().toString(), 1L, "unexpected")))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static CalciteQueryResult result(List<List<Object>> rows) {
    return new CalciteQueryResult(List.of(), rows, false, rows.size(), 0, null);
  }

  @Test
  void repeatedLargeLabelsAreBoundedBeforeAggregateSerialization() {
    var f = new Fixture(40, TARGET_ID, true);
    assertThatThrownBy(
            () ->
                RecordReferenceSnapshots.capture(
                    f.values,
                    f.prepared,
                    f.plan,
                    compiler,
                    query -> result(List.of(List.of(TARGET_ID, 1L, "字".repeat(25_000))))))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(
                        org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode
                            .RECORD_REFERENCE_CONFLICT));
  }

  @Test
  void truncatedReadCannotSilentlyBecomeAnIncompleteHistoricalSnapshot() {
    var f = new Fixture(1, TARGET_ID, true);
    assertThatThrownBy(
            () ->
                RecordReferenceSnapshots.capture(
                    f.values,
                    f.prepared,
                    f.plan,
                    compiler,
                    query -> new CalciteQueryResult(List.of(), List.of(), true, 0, 0, null)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static RuntimeRecordModel model(
      String id, List<LogicalFieldDefinition> fields, List<LogicalReferenceDefinition> refs) {
    return new RuntimeRecordModel(
        id,
        "app",
        id,
        1,
        new PhysicalSchemaDefinition("v1", fields, List.of(), null, true, refs),
        new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("source", "catalog", "schema", id)));
  }

  private static RecordAccessContext access() {
    return new RecordAccessContext(
        new RuntimeActor("tenant", "user"),
        "target-page",
        Set.of("id", "record_version", "name"),
        Set.of(),
        Set.of(),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "user", Set.of()),
        null,
        1);
  }

  private static final class Fixture {
    final OwnedRecordView values;
    final RecordWritePreparation prepared;
    final RecordReferenceAdmission.Plan plan;

    Fixture(int count, String value, boolean targetAvailable) {
      var root = model("root", List.of(), List.of());
      final var target =
          model(
              "target",
              List.of(
                  new LogicalFieldDefinition(
                      "name",
                      "name",
                      "Name",
                      LogicalFieldType.STRING,
                      true,
                      200,
                      null,
                      null,
                      null)),
              List.of());
      var reference =
          new LogicalReferenceDefinition(
              "product",
              "product",
              "target",
              false,
              List.of(new LogicalReferenceField("name", LogicalFieldType.STRING)));
      final var detail =
          model(
              "detail",
              List.of(
                  new LogicalFieldDefinition(
                      "product",
                      "product_id",
                      "Product",
                      LogicalFieldType.UUID,
                      true,
                      null,
                      null,
                      null,
                      null)),
              List.of(reference));
      final var rule =
          new RecordReferenceRule(
              "1",
              "product",
              new RecordValidationField("items", "product"),
              "target",
              "app",
              "target",
              "target-page",
              null,
              null,
              "name");
      var parent = new RecordView(UUID.randomUUID().toString(), 1, Map.of());
      var rows = new ArrayList<RecordView>();
      var checks = new ArrayList<RecordReferenceCheck>();
      for (int index = 0; index < count; index++) {
        var row = new RecordView(UUID.randomUUID().toString(), 1, Map.of("product_id", value));
        rows.add(row);
        checks.add(new RecordReferenceCheck("product", "detail", row.id(), TARGET_ID, false));
      }
      values = new OwnedRecordView(parent, Map.of("detail", rows));
      var snapshot =
          new RecordSnapshotPlan(
              parent.id(),
              1,
              List.of(
                  new RecordSnapshotPlan.Part(root, access(), null, true),
                  new RecordSnapshotPlan.Part(detail, access(), null, false)),
              new RecordSnapshotValidation(root, access(), List.of()));
      prepared =
          new RecordWritePreparation(
              List.of(
                  new RecordSqlStatement(
                      "test", List.of(), RecordMutationType.UPDATE, parent.id(), 1, Instant.EPOCH)),
              snapshot,
              1,
              checks);
      plan =
          new RecordReferenceAdmission.Plan(
              null,
              Map.of("root", root, "detail", detail),
              Map.of(
                  "product",
                  new RecordReferenceAdmission.Binding(
                      rule,
                      "detail",
                      targetAvailable
                          ? new RecordReferenceAdmission.Target(
                              target,
                              access(),
                              new RecordReferenceAdmission.TargetSnapshot(0, null))
                          : null)));
    }
  }
}
