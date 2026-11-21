package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.LINK;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.UNLINK;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordCollectionMutationPlanTest {
  private final RecordSqlCompiler compiler =
      new RecordSqlCompiler(new StandardLogicalValueConverter());
  private final RecordCollectionBinding binding =
      RecordCollectionBindingTest.binding("projects", "members", "edges", "source_id", "target_id");
  private final RecordAccessContext access = access("tenant", "actor");

  @Test
  void retainsAllTwoHundredOneWritesAndAllHundredResults() {
    var mutations =
        IntStream.rangeClosed(2, 101)
            .mapToObj(i -> new CollectionEdgeMutation(id(i), 0L, -1L, LINK))
            .toList();
    var command = command(mutations);
    var plan = prepare(binding, command, observations(command));
    assertThat(plan.statements()).hasSize(201);
    assertThat(
            plan.statements().stream()
                .map(statement -> statement.subject().modelId())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting())))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("projects", 1L, "members", 100L, "membership", 100L));
    assertThat(plan.result().edges())
        .hasSize(100)
        .allSatisfy(
            edge -> {
              assertThat(edge.targetVersion()).isEqualTo(1);
              assertThat(edge.edgeVersion()).isZero();
              assertThat(edge.changed()).isTrue();
            });
    assertThat(RecordCollectionMutationPlan.locks(binding, command, "tenant", () -> {}).sources())
        .hasSize(201);
    var entry = RecordCollectionReceipt.accepted(binding, plan.result());
    assertThat(RecordCollectionReceipt.decode(RecordCollectionReceipt.encode(entry)))
        .isEqualTo(entry);
  }

  @Test
  void noOpStillLocksAndChecksEveryObservedEndpointAndEdge() {
    var command = command(List.of(new CollectionEdgeMutation(id(2), 0L, -1L, UNLINK)));
    var plan = prepare(binding, command, observations(command));
    assertThat(plan.statements()).isEmpty();
    assertThat(plan.result().sourceVersion()).isZero();
    assertThat(plan.result().edges().getFirst().edgeVersion()).isEqualTo(-1);
    assertThat(RecordCollectionMutationPlan.locks(binding, command, "tenant", () -> {}).sources())
        .hasSize(3);
    var states = Map.of(id(2), RecordCollectionEdgeState.absent());
    conflict(
        () ->
            prepare(
                binding,
                command,
                new RecordCollectionMutationPlan.Observations(1, Map.of(id(2), 0L), states)));
    conflict(
        () ->
            prepare(
                binding,
                command,
                new RecordCollectionMutationPlan.Observations(0, Map.of(id(2), 1L), states)));
    conflict(
        () ->
            prepare(
                binding,
                command,
                new RecordCollectionMutationPlan.Observations(
                    0, Map.of(id(2), 0L), Map.of(id(2), new RecordCollectionEdgeState(0, false)))));
    conflict(
        () ->
            prepare(
                binding,
                command,
                new RecordCollectionMutationPlan.Observations(0, Map.of(), states)));
    conflict(
        () ->
            prepare(
                binding,
                command,
                new RecordCollectionMutationPlan.Observations(
                    0, Map.of(id(2), 0L, id(3), 0L), states)));
  }

  @Test
  void selfLinkTouchesItsEndpointOnceAndRejectsInconsistentSelfObservations() {
    var self =
        RecordCollectionBindingTest.binding(
            "projects", "projects", "edges", "source_id", "target_id");
    var command = command(List.of(new CollectionEdgeMutation(id(1), 0L, -1L, LINK)));
    var plan = prepare(self, command, observations(command));
    assertThat(plan.statements()).hasSize(2);
    assertThat(plan.result().sourceVersion()).isEqualTo(1);
    assertThat(plan.result().edges().getFirst().targetVersion()).isEqualTo(1);
    assertThat(RecordCollectionMutationPlan.locks(self, command, "tenant", () -> {}).sources())
        .hasSize(2);
    var inconsistent = command(List.of(new CollectionEdgeMutation(id(1), 1L, -1L, LINK)));
    conflict(() -> prepare(self, inconsistent, observations(inconsistent)));
  }

  @Test
  void unchangedSelfEdgeReportsTheRevisionAdvancedByAnotherChangedEdge() {
    var self =
        RecordCollectionBindingTest.binding(
            "projects", "projects", "edges", "source_id", "target_id");
    var command =
        command(
            List.of(
                new CollectionEdgeMutation(id(1), 0L, -1L, UNLINK),
                new CollectionEdgeMutation(id(2), 0L, -1L, LINK)));
    var plan = prepare(self, command, observations(command));
    assertThat(plan.statements()).hasSize(3);
    var unchanged = plan.result().edges().getFirst();
    assertThat(unchanged.changed()).isFalse();
    assertThat(unchanged.targetVersion()).isEqualTo(1);
    assertThat(unchanged.edgeVersion()).isEqualTo(-1);
    assertThat(RecordCollectionMutationPlan.locks(self, command, "tenant", () -> {}).sources())
        .hasSize(4);
  }

  @Test
  void identicalRecordIdentifiersInDifferentModelsRetainBothEndpointWrites() {
    var command = command(List.of(new CollectionEdgeMutation(id(1), 0L, -1L, LINK)));
    assertThat(prepare(binding, command, observations(command)).statements()).hasSize(3);
    assertThat(RecordCollectionMutationPlan.locks(binding, command, "tenant", () -> {}).sources())
        .hasSize(3);
  }

  @Test
  void mismatchedActorOrTenantCannotSupplyTargetAccess() {
    var command = command(List.of(new CollectionEdgeMutation(id(2), 0L, -1L, LINK)));
    for (var other : List.of(access("other", "actor"), access("tenant", "other"))) {
      assertThatThrownBy(
              () ->
                  RecordCollectionMutationPlan.prepare(
                      binding,
                      command,
                      access,
                      other,
                      observations(command),
                      Instant.EPOCH,
                      compiler))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  private RecordCollectionMutationPlan prepare(
      RecordCollectionBinding resolved,
      CollectionRecordCommand command,
      RecordCollectionMutationPlan.Observations observations) {
    return RecordCollectionMutationPlan.prepare(
        resolved, command, access, access, observations, Instant.EPOCH, compiler);
  }

  private static RecordCollectionMutationPlan.Observations observations(
      CollectionRecordCommand command) {
    return new RecordCollectionMutationPlan.Observations(
        command.expectedSourceVersion(),
        command.mutations().stream()
            .collect(
                Collectors.toMap(
                    value -> value.targetRecordId(), value -> value.expectedTargetVersion())),
        command.mutations().stream()
            .collect(
                Collectors.toMap(
                    value -> value.targetRecordId(), value -> RecordCollectionEdgeState.absent())));
  }

  private static CollectionRecordCommand command(List<CollectionEdgeMutation> mutations) {
    return new CollectionRecordCommand(
        "1",
        "projects",
        new RecordSchemaReference(1, "a".repeat(64)),
        "members",
        id(1),
        0L,
        mutations,
        "plan",
        30_000);
  }

  private static RecordAccessContext access(String tenant, String actor) {
    return new RecordAccessContext(
        new RuntimeActor(tenant, actor),
        "resource",
        Set.of("id", "record_version"),
        Set.of(),
        Set.of(),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", actor, Set.of()),
        "department");
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }

  private static void conflict(Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
  }
}
