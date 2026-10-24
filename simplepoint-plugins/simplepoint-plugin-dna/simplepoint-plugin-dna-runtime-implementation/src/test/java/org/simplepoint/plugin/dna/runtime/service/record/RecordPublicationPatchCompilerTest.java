package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchCompiler.Direction.APPLY;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchCompiler.Direction.RESTORE;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RecordPublicationPatchCompilerTest {
  private final RecordPublicationPatchFixture fixture = new RecordPublicationPatchFixture("database", "forms");

  @Test
  void fixtureCandidatesAreProducedByTheRealFrozenCalculationEngine() {
    fixture.verifyCalculatedCandidates();
  }

  @Test
  void resolvesStableFieldsToRenamedColumnsAndPreservesUndoAuditAndExactTypes() {
    var apply = fixture.compiler.compile(fixture.intent, fixture.aggregate, fixture.models, APPLY);
    var restore = fixture.compiler.compile(fixture.intent, fixture.aggregate, fixture.models, RESTORE);
    assertThat(apply).hasSize(2);
    assertThat(apply.getFirst().sql()).contains("\"total_amount\" = ?", "\"record_version\" = ?", "\"tenant_id\" = ?")
        .doesNotContain("old_", "created_by", "created_at", "source_value", "候选", "seed-actor", "publisher");
    assertThat(apply.getLast().sql()).contains("AND \"owner_record_id\" = ?").doesNotContain("row_order", "memo");
    assertThat(apply.getFirst().resultVersion()).isEqualTo(1);
    assertThat(apply.getFirst().updatedAt()).isEqualTo(RecordPublicationPatchFixture.AT);
    assertThat(restore.getFirst().resultVersion()).isZero();
    assertThat(restore.getFirst().updatedAt()).isEqualTo(RecordPublicationPatchFixture.BEFORE);
    assertThat(apply.getFirst().parameters()).contains(new BigDecimal("9999999999999999999999999999999999.9999"), Long.MIN_VALUE,
        "候选值・日本語😀", "publisher", "tenant-a", "seed-actor");
    assertThat(restore.getFirst().parameters()).contains(new BigDecimal("1234567890123456789012345678901234.1234"), Long.MAX_VALUE,
        "原值・日本語😀", "seed-actor", "publisher");
    assertThat(apply.getFirst().parameters().stream().filter(byte[].class::isInstance).map(byte[].class::cast).toList())
        .singleElement().satisfies(value -> assertThat(value).containsExactly((byte) 0xff));
    assertThat(restore.getFirst().parameters().stream().filter(byte[].class::isInstance).map(byte[].class::cast).toList())
        .singleElement().satisfies(value -> assertThat(value).containsExactly((byte) 0xfb, (byte) 0xff));
    // Ordinary Record CREATE uses the identical canonical Base64 conversion path.
    assertThat(fixture.seed().getFirst().parameters().stream().filter(byte[].class::isInstance).map(byte[].class::cast).toList())
        .singleElement().satisfies(value -> assertThat(value).containsExactly((byte) 0xfb, (byte) 0xff));
  }

  @Test
  void rejectsChangedGenerationsPlacementsVersionsOwnersAndSchemasBeforeExecution() {
    var parent = fixture.parent;
    for (var model : List.of(
        new RuntimeRecordModel(parent.physicalModelId(), "other-app", parent.formId(), 2, parent.activeSchema(), 1,
            parent.requestedSchema(), parent.target()),
        new RuntimeRecordModel(parent.physicalModelId(), parent.appId(), "other-form", 2, parent.activeSchema(), 1,
            parent.requestedSchema(), parent.target()),
        new RuntimeRecordModel(parent.physicalModelId(), parent.appId(), parent.formId(), 3, parent.activeSchema(), 1,
            parent.requestedSchema(), parent.target()),
        new RuntimeRecordModel(parent.physicalModelId(), parent.appId(), parent.formId(), 2, parent.activeSchema(), 2,
            parent.requestedSchema(), parent.target()),
        new RuntimeRecordModel(parent.physicalModelId(), parent.appId(), parent.formId(), 2, parent.requestedSchema(), 1,
            parent.requestedSchema(), parent.target()),
        new RuntimeRecordModel(parent.physicalModelId(), parent.appId(), parent.formId(), 2, parent.activeSchema(), 1,
            parent.requestedSchema(), new PhysicalSchemaTarget(parent.physicalModelId(),
                new RuntimeMetadataTarget("other-source", "database", "forms", "patch_parents"))))) {
      var models = new LinkedHashMap<>(fixture.models);
      models.put(parent.physicalModelId(), new RecordPublicationPatchCompiler.Resolved(model, 7));
      incompatible(() -> fixture.compiler.compile(fixture.intent, fixture.aggregate, models, APPLY));
    }
    var models = new LinkedHashMap<>(fixture.models);
    models.put(parent.physicalModelId(), new RecordPublicationPatchCompiler.Resolved(parent, 8));
    incompatible(() -> fixture.compiler.compile(fixture.intent, fixture.aggregate, models, RESTORE));
    models.remove(fixture.child.physicalModelId());
    incompatible(() -> fixture.compiler.compile(fixture.intent, fixture.aggregate, models, APPLY));
  }

  @Test
  void persistedPatchesMustBeCanonicalAndValidForTheFrozenPhysicalType() {
    for (var test : List.of(
        Map.entry("amount", RecordPublicationPatchFixture.change(LogicalValueKind.DECIMAL, "1.0000", "1.1")),
        Map.entry("amount", RecordPublicationPatchFixture.change(LogicalValueKind.STRING, "1.0000", "2.0000")),
        Map.entry("number", RecordPublicationPatchFixture.change(LogicalValueKind.INTEGER, "1", "9223372036854775808")),
        Map.entry("number", RecordPublicationPatchFixture.change(LogicalValueKind.INTEGER, "1", "+2")),
        Map.entry("number", RecordPublicationPatchFixture.change(LogicalValueKind.INTEGER, "1", "oops")),
        Map.entry("time", RecordPublicationPatchFixture.change(LogicalValueKind.TIME, "00:00", "00:00:00.0000001")),
        Map.entry("binary", RecordPublicationPatchFixture.change(LogicalValueKind.BINARY, "+/8", "/w==")),
        Map.entry("json", RecordPublicationPatchFixture.change(LogicalValueKind.JSON, "{}", "{\"x\":1,\"x\":2}")))) {
      var changes = new LinkedHashMap<>(fixture.aggregate.rows().getFirst().changes());
      changes.put(test.getKey(), test.getValue());
      var aggregate = new RecordPublicationAggregate(1, RecordPublicationPatchFixture.PARENT,
          List.of(fixture.row(fixture.parent.physicalModelId(), RecordPublicationPatchFixture.PARENT, null, changes)));
      for (var direction : RecordPublicationPatchCompiler.Direction.values()) {
        assertThatThrownBy(() -> fixture.compiler.compile(fixture.intent, aggregate, fixture.models, direction))
            .isInstanceOf(DnaRuntimeException.class);
      }
    }
  }

  @Test
  void evenTrustedCandidateCannotTargetDetailOwnershipOrOrdinalColumns() {
    for (String target : List.of("owner", "ordinal")) {
      var original = fixture.intent.command();
      var details = Map.of("lines", new RecordCalculationScope(fixture.child.physicalModelId(), fixture.reference(fixture.child, false),
          Map.of(target, "1")));
      var command = new RecordWritePolicyPublicationCommand(original.physicalModelId(), original.appId(), original.formId(),
          original.schemaReference(), original.expectedVersion(), original.targetVersion(), original.publicationKey(), original.policyChecksum(),
          original.checks(), original.timeoutMs(), original.valueChecks(), new RecordCalculationDefinition("1", original.calculation().formulas(), details));
      var intent = new RecordPublicationIntent(1, fixture.intent.key(), command, "f".repeat(64), fixture.intent.previousFingerprint(),
          fixture.intent.actorId(), fixture.intent.evaluatedAt(), fixture.intent.bindings(), null);
      var aggregate = new RecordPublicationAggregate(1, RecordPublicationPatchFixture.PARENT,
          List.of(fixture.aggregate.rows().getFirst(), fixture.row(fixture.child.physicalModelId(), RecordPublicationPatchFixture.CHILD,
              RecordPublicationPatchFixture.PARENT, Map.of(target, RecordPublicationPatchFixture.change(LogicalValueKind.INTEGER, "1", "2")))));
      incompatible(() -> fixture.compiler.compile(intent, aggregate, fixture.models, APPLY));
    }
  }

  @Test
  void childOnlyChangesTouchParentOnceAndEmptyAggregatesStillValidateBindings() {
    var root = fixture.row(fixture.parent.physicalModelId(), RecordPublicationPatchFixture.PARENT, null, Map.of());
    var childOnly = new RecordPublicationAggregate(1, RecordPublicationPatchFixture.PARENT,
        List.of(fixture.aggregate.rows().getLast(), root));
    var plans = fixture.compiler.compile(fixture.intent, childOnly, fixture.models, APPLY);
    assertThat(plans).hasSize(2);
    assertThat(plans.getFirst().recordId()).isEqualTo(RecordPublicationPatchFixture.PARENT);
    assertThat(plans.getFirst().sql()).contains("SET \"record_version\" = ?").doesNotContain("\"total_amount\" =");
    var noop = new RecordPublicationAggregate(1, RecordPublicationPatchFixture.PARENT, List.of(root));
    assertThat(fixture.compiler.compile(fixture.intent, noop, fixture.models, APPLY)).isEmpty();
    incompatible(() -> fixture.compiler.compile(fixture.intent, noop, Map.of(), APPLY));
  }

  private static void incompatible(Runnable call) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
  }
}
