package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.record.RecordCalculationWriteSupport.Scope;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;

class RecordSequenceLockDiscoveryTest {
  private final String root = UUID.randomUUID().toString();
  private final String child = UUID.randomUUID().toString();
  private final String oldTarget = UUID.randomUUID().toString();
  private final String nextTarget = UUID.randomUUID().toString();
  private final String lastTarget = UUID.randomUUID().toString();
  private final Scope parent = scope("parent");
  private final Scope detail = scope("child");

  @Test
  void collectsOldAndEveryFutureReferenceAcrossRenamedFieldsAndOwnedRowsWithoutEvaluatingSteps() {
    var reads = new ArrayList<String>();
    var plan =
        RecordSequenceLockDiscovery.discover(
            parent,
            Map.of("items", detail),
            List.of(intent(root, 4, nextTarget), intent(root, 5, lastTarget)),
            (scope, id, isParent) -> {
              assertThat(id).isEqualTo(root);
              reads.add(scope.model().physicalModelId());
              return List.of(
                  new RecordView(
                      isParent ? root : child, 4, Map.of("current_customer", oldTarget)));
            });
    assertThat(reads).containsExactly("parent", "child");
    assertThat(plan.sources())
        .containsExactly(
            new RecordSequenceLockPlan.Source("parent", root),
            new RecordSequenceLockPlan.Source("child", child));
    assertThat(plan.references())
        .containsExactly(
            reference("parent", oldTarget), reference("child", oldTarget),
            reference("parent", nextTarget), reference("parent", lastTarget));
    plan.verifyReads().run();
    assertThat(reads).containsExactly("parent", "child", "parent", "child");
  }

  @Test
  void detectsVersionChangesAndOwnedMembershipChangesAfterOrderedLocks() {
    for (boolean changeRoot : List.of(true, false)) {
      var rootRows = new AtomicReference<>(List.of(new RecordView(root, 0, Map.of())));
      var childRows = new AtomicReference<>(List.of(new RecordView(child, 0, Map.of())));
      var plan =
          RecordSequenceLockDiscovery.discover(
              parent,
              Map.of("items", detail),
              List.of(intent(root, 0, nextTarget)),
              (scope, id, isParent) -> isParent ? rootRows.get() : childRows.get());
      if (changeRoot) {
        rootRows.set(List.of(new RecordView(root, 1, Map.of())));
      } else {
        childRows.set(List.of());
      }
      assertThatThrownBy(plan.verifyReads()::run)
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              error ->
                  assertThat(error.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));
    }
  }

  @Test
  void createsDoNotReadAnUncommittedRowAndNullReferencesDoNotAddLocks() {
    var nullable = new java.util.HashMap<String, Object>();
    nullable.put("old_customer", null);
    var plan =
        RecordSequenceLockDiscovery.discover(
            parent,
            Map.of("items", detail),
            List.of(
                new RecordSequenceLockPlan.Intent(
                    new RecordCreateMutation("create", nullable), root),
                intent(root, 0, nextTarget)),
            (scope, id, isParent) -> {
              throw new AssertionError("New row must not be read");
            });
    assertThat(plan.sources()).containsExactly(new RecordSequenceLockPlan.Source("parent", root));
    assertThat(plan.references()).containsExactly(reference("parent", nextTarget));
    plan.verifyReads().run();
  }

  @Test
  void rejectsUnknownFieldsInvalidReferenceIdentitiesAndUnauthorizedMissingRecords() {
    for (var values :
        List.of(
            Map.<String, Object>of("unknown", nextTarget),
            Map.<String, Object>of("old_customer", "invalid"))) {
      assertThatThrownBy(
              () ->
                  RecordSequenceLockDiscovery.discover(
                      parent,
                      Map.of(),
                      List.of(
                          new RecordSequenceLockPlan.Intent(
                              new RecordCreateMutation("create", values), root)),
                      (scope, id, isParent) -> List.of()))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThatThrownBy(
            () ->
                RecordSequenceLockDiscovery.discover(
                    parent,
                    Map.of(),
                    List.of(intent(root, 0, nextTarget)),
                    (scope, id, isParent) -> List.of()))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error -> assertThat(error.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_NOT_FOUND));
    assertThatThrownBy(
            () ->
                RecordSequenceLockDiscovery.discover(
                    parent,
                    Map.of(),
                    java.util.Collections.nCopies(101, intent(root, 0, nextTarget)),
                    (scope, id, isParent) -> List.of()))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
  }

  private RecordSequenceLockPlan.Intent intent(String id, long version, String target) {
    return new RecordSequenceLockPlan.Intent(
        new RecordUpdateMutation("step" + version, id, version, Map.of("old_customer", target)),
        id);
  }

  private RecordSequenceLockPlan.Reference reference(String model, String id) {
    return new RecordSequenceLockPlan.Reference(model, "customer", id);
  }

  private static Scope scope(String id) {
    var active = schema("current_customer");
    var historical = schema("old_customer");
    return new Scope(
        new RuntimeRecordModel(
            id,
            "app",
            id,
            2,
            active,
            1,
            historical,
            new PhysicalSchemaTarget(id, new RuntimeMetadataTarget("store", "db", "public", id))),
        null);
  }

  private static PhysicalSchemaDefinition schema(String column) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(
            new LogicalFieldDefinition(
                "stable-customer",
                column,
                "Customer",
                LogicalFieldType.UUID,
                true,
                null,
                null,
                null,
                null)),
        List.of(),
        null,
        true,
        List.of(new LogicalReferenceDefinition("customer", "stable-customer", "target", false)));
  }
}
