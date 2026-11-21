package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRebindMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordCollectionRebindDatabaseTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "publisher");
  private static final PhysicalSchemaCodec CODEC = new PhysicalSchemaCodec(new ObjectMapper());

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void copiesInBatchesAndRestoresOrCommitsTheExactMappedEdges(boolean postgres) throws Exception {
    try (var fixture = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres)) {
      var original = RecordCollectionSqlDatabaseTest.install(fixture, false, "former");
      var destination = RecordCollectionSqlDatabaseTest.install(fixture, false, "replacement");
      var root = fixture.create(original.source(), Map.of());
      fixture.save(original.source(), root);
      var targets = new LinkedHashMap<String, String>();
      var prior = new ArrayList<RecordCollectionEdgeKey>();
      for (int index = 0; index < 12; index++) {
        var from = fixture.create(original.target(), Map.of());
        var to = fixture.create(destination.target(), Map.of());
        fixture.save(original.target(), from);
        fixture.save(destination.target(), to);
        targets.put(from.recordId(), to.recordId());
        var edge =
            RecordCollectionEdgeKey.of(
                original, ACTOR.tenantId(), root.recordId(), from.recordId());
        prior.add(edge);
        RecordCollectionSqlDatabaseTest.execute(
            fixture,
            original,
            edge,
            index,
            0,
            RecordCollectionEdgeState.absent(),
            CollectionEdgeMutation.Mode.LINK);
      }
      new RecordJournalProvisioner(fixture.schemaBackend).prepare(original.source().target());
      var liveEntrance = new AtomicBoolean(true);
      var failCutover = new AtomicBoolean(false);
      var coordinator = coordinator(fixture, original, destination, liveEntrance, failCutover);
      var initial = command(original, null, 0, Map.of());
      assertThat(finish(coordinator, initial, false).checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);

      var incomplete = command(original, destination, 1, Map.of());
      coordinator.advance(ACTOR, incomplete, false);
      assertCode(
          () -> coordinator.advance(ACTOR, incomplete, false),
          DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE);
      assertThat(fixture.count(destination.edge())).isZero();
      assertThat(finish(coordinator, incomplete, true).checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);

      var duplicates = new LinkedHashMap<>(targets);
      duplicates.put(prior.get(1).targetId(), targets.get(prior.getFirst().targetId()));
      var conflicting = command(original, destination, 2, duplicates);
      coordinator.advance(ACTOR, conflicting, false);
      assertCode(
          () -> finish(coordinator, conflicting, false),
          DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE);
      assertThat(fixture.count(destination.edge())).isZero();
      assertThat(finish(coordinator, conflicting, true).checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);

      var cancellable = command(original, destination, 3, targets);
      var progress = coordinator.advance(ACTOR, cancellable, false);
      assertThat(progress.intent().contractVersion()).isEqualTo(6);
      assertThat(progress.checkpoint().total()).isEqualTo(12);
      for (int i = 0; i < 8 && progress.checkpoint().applied() == 0; i++) {
        progress = coordinator.advance(ACTOR, cancellable, false);
      }
      assertThat(progress.checkpoint().applied()).isEqualTo(10);
      assertThat(fixture.count(destination.edge())).isEqualTo(10);
      assertOldLinks(fixture, original, prior, true);
      liveEntrance.set(false);
      var restarted = coordinator(fixture, original, destination, liveEntrance, failCutover);
      assertThat(finish(restarted, cancellable, true).checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
      assertThat(fixture.count(destination.edge())).isZero();
      assertOldLinks(fixture, original, prior, true);

      liveEntrance.set(true);
      failCutover.set(true);
      var rebind = command(original, destination, 4, targets);
      assertCode(
          () -> finish(restarted, rebind, false), DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED);
      assertOldLinks(fixture, original, prior, true);
      assertThat(fixture.count(destination.edge())).isEqualTo(12);
      var committed = finish(restarted, rebind, false);
      assertThat(committed.checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
      assertOldLinks(fixture, original, prior, false);
      assertThat(fixture.count(original.edge())).isEqualTo(12);
      assertThat(fixture.count(destination.edge())).isEqualTo(12);
      for (var target : targets.values()) {
        var id =
            RecordCollectionEdgeKey.of(destination, ACTOR.tenantId(), root.recordId(), target).id();
        assertThat(fixture.value(destination.edge(), id, destination.targetColumn()))
            .isEqualTo(target);
        assertThat(fixture.value(destination.edge(), id, "created_org_dept_id"))
            .isEqualTo("department");
      }
      var installed = fixture.backend.calculation(original.source(), ACTOR.tenantId(), 5, 30_000);
      assertThat(installed.policy().definition().collectionsOrEmpty())
          .containsExactly(rebind.rebindDestination());
      assertThat(installed.policy().retiredRelations()).containsExactly(original.relationId());
      liveEntrance.set(false);
      assertThat(restarted.advance(ACTOR, rebind, false)).isEqualTo(committed);
    }
  }

  private static void assertOldLinks(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      List<RecordCollectionEdgeKey> edges,
      boolean active)
      throws Exception {
    for (var edge : edges) {
      assertThat(fixture.value(binding.edge(), edge.id(), binding.sourceColumn()))
          .isEqualTo(active ? edge.sourceId() : null);
      assertThat(fixture.value(binding.edge(), edge.id(), binding.targetColumn()))
          .isEqualTo(active ? edge.targetId() : null);
      assertThat(((Number) fixture.value(binding.edge(), edge.id(), "record_version")).longValue())
          .isEqualTo(active ? 0 : 1);
    }
  }

  private static RecordPublicationCoordinator coordinator(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding original,
      RecordCollectionBinding destination,
      AtomicBoolean liveEntrance,
      AtomicBoolean failCutover) {
    var model = original.source();
    var control = mock(RecordPublicationControlFence.class);
    when(control.execute(eq(ACTOR.tenantId()), any(), any(RecordExecutionDeadline.class), any()))
        .thenAnswer(
            call -> {
              Function<Map<String, PhysicalModelEntity>, Object> work = call.getArgument(3);
              return work.apply(Map.of());
            });
    var metadata = mock(RecordPublicationMetadata.class);
    var models =
        Map.of(
            model.physicalModelId(),
            new RecordPublicationPatchCompiler.Resolved(model, 0, Map.of()));
    var bindings = Map.of(model.physicalModelId(), binding(model));
    var resources =
        Map.of(model.physicalModelId(), Map.<String, RecordPublicationIntent.Resource>of());
    when(metadata.resolve(eq(ACTOR), any(), any(), anyBoolean()))
        .thenReturn(
            new RecordPublicationMetadata.Snapshot(
                models,
                bindings,
                resources,
                Map.of(),
                Map.of(original.relationId(), binding(original.edge()))));
    when(metadata.resolve(eq(ACTOR), any(), any(), anyBoolean(), anyBoolean()))
        .thenAnswer(
            call -> {
              boolean live = call.getArgument(3);
              boolean transfer = call.getArgument(4);
              if (live && !liveEntrance.get()) {
                throw RecordReferenceAdmission.incompatible();
              }
              var entrance =
                  live
                      ? new RecordPublicationIntent.Resource(
                          "binding",
                          new RecordPublicationIntent.Projection(
                              "projection", "owner", 1, destination.target().physicalModelId()))
                      : null;
              return new RecordPublicationMetadata.Snapshot(
                  models,
                  bindings,
                  resources,
                  Map.of(),
                  live ? Map.of(destination.relationId(), binding(destination.edge())) : Map.of(),
                  live ? Map.of(original.relationId(), original.edge()) : Map.of(),
                  live ? Map.of(original.relationId(), binding(original.edge())) : Map.of(),
                  null,
                  transfer
                      ? new RecordPublicationMetadata.Rebind(
                          new RecordPublicationMetadata.Conversion(
                              original, binding(original.edge()), binding(original.target()), null),
                          new RecordPublicationMetadata.Conversion(
                              destination,
                              binding(destination.edge()),
                              binding(destination.target()),
                              entrance))
                      : null);
            });
    var backend = spy(fixture.backend);
    doAnswer(
            call -> {
              Function<RecordPublicationSession, Object> work = call.getArgument(4);
              return fixture.backend.publication(
                  call.getArgument(0),
                  call.getArgument(1),
                  call.getArgument(2),
                  call.getArgument(3),
                  session -> {
                    var result = work.apply(session);
                    if (result instanceof RecordPublicationStore.Entry entry
                        && entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.COMMITTED
                        && failCutover.compareAndSet(true, false)) {
                      throw new DnaRuntimeException(
                          DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Injected cutover failure");
                    }
                    return result;
                  });
            })
        .when(backend)
        .publication(any(), anyList(), any(), anyInt(), any());
    return new RecordPublicationCoordinator(
        control,
        metadata,
        backend,
        new RecordPublicationBatchExecutor(CODEC, fixture.compiler),
        new RecordRequestFingerprint(new ObjectMapper()));
  }

  private static RecordWritePolicyPublicationCommand command(
      RecordCollectionBinding original,
      RecordCollectionBinding destination,
      int expected,
      Map<String, String> mapping) {
    var source = original.source();
    var desired = rule(destination == null ? original : destination);
    var definition =
        new RecordCalculationDefinition(
            "1", Map.of(), Map.of(), null, null, null, null, null, List.of(desired));
    return new RecordWritePolicyPublicationCommand(
        source.physicalModelId(),
        source.appId(),
        source.formId(),
        binding(source).requestedSchema(),
        expected,
        expected + 1,
        "rebind-" + expected,
        "a".repeat(64),
        Map.of(),
        30_000,
        Map.of(),
        definition,
        destination == null ? List.of() : List.of(rule(original)),
        null,
        destination == null
            ? null
            : new RecordCollectionRebindMigration(
                original.relationId(), destination.relationId(), mapping));
  }

  private static RecordCollectionRule rule(RecordCollectionBinding binding) {
    var target = binding.target();
    return new RecordCollectionRule(
        "1",
        binding.relationId(),
        binding.edge().physicalModelId(),
        binding(binding.edge()).requestedSchema(),
        target.physicalModelId(),
        target.appId(),
        target.formId(),
        "resource-" + binding.relationId(),
        null,
        null,
        null);
  }

  private static RecordPublicationIntent.Binding binding(RuntimeRecordModel model) {
    var reference =
        new RecordSchemaReference(
            model.activeVersion(), CODEC.encode(model.activeSchema()).checksum());
    return new RecordPublicationIntent.Binding(
        reference, reference, model.target().metadataTarget(), 0);
  }

  private static RecordPublicationStore.Entry finish(
      RecordPublicationCoordinator coordinator,
      RecordWritePolicyPublicationCommand command,
      boolean cancel) {
    var entry = coordinator.advance(ACTOR, command, cancel);
    for (int attempts = 0; attempts < 12 && entry.checkpoint().progress() != null; attempts++) {
      entry = coordinator.advance(ACTOR, command, cancel);
    }
    assertThat(entry.checkpoint().progress()).isNull();
    return entry;
  }

  private static void assertCode(Runnable work, DnaRuntimeErrorCode code) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
