package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Real target transactions and recovery; control metadata is deliberately replaced for fault
 * injection.
 */
class RecordCollectionPublicationCoordinatorDatabaseTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "publisher");
  private static final PhysicalSchemaCodec CODEC = new PhysicalSchemaCodec(new ObjectMapper());

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void changedCollectionBindingStopsPublicationButUnavailableTargetsDoNotBlockRestoration(
      boolean postgres) throws Exception {
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres)) {
      var binding = RecordCollectionSqlDatabaseTest.install(f);
      f.save(binding.source(), f.create(binding.source(), Map.of()));
      new RecordJournalProvisioner(f.schemaBackend).prepare(binding.source().target());
      var control = mock(RecordPublicationControlFence.class);
      var metadata = mock(RecordPublicationMetadata.class);
      var scopes = new ArrayList<Set<String>>();
      var deadlines = new ArrayList<RecordExecutionDeadline>();
      when(control.execute(anyString(), anyCollection(), any(RecordExecutionDeadline.class), any()))
          .thenAnswer(
              call -> {
                Collection<String> ids = call.getArgument(1);
                scopes.add(Set.copyOf(ids));
                deadlines.add(call.getArgument(2));
                Function<Map<String, PhysicalModelEntity>, Object> work = call.getArgument(3);
                return work.apply(Map.of());
              });
      var source = binding.source();
      var frozenEdge = frozen(binding.edge());
      var current = new AtomicReference<>(frozenEdge);
      var available = new AtomicBoolean(true);
      when(metadata.resolve(eq(ACTOR), any(), anyMap(), eq(true)))
          .thenAnswer(
              call -> {
                if (!available.get()) {
                  throw RecordReferenceAdmission.incompatible();
                }
                return snapshot(binding, Map.of(binding.relationId(), current.get()));
              });
      when(metadata.resolve(eq(ACTOR), any(), anyMap(), eq(false)))
          .thenReturn(snapshot(binding, Map.of()));
      var coordinator =
          new RecordPublicationCoordinator(
              control,
              metadata,
              f.backend,
              new RecordPublicationBatchExecutor(CODEC, f.compiler),
              new RecordRequestFingerprint(new ObjectMapper()));
      var command = command(binding, 0);
      var first = coordinator.advance(ACTOR, command, false);
      assertThat(first.intent().contractVersion()).isEqualTo(3);
      assertThat(scopes.getLast())
          .containsExactlyInAnyOrder(
              source.physicalModelId(),
              binding.target().physicalModelId(),
              binding.edge().physicalModelId());
      current.set(
          new RecordPublicationIntent.Binding(
              new RecordSchemaReference(2, "b".repeat(64)),
              frozenEdge.requestedSchema(),
              frozenEdge.target(),
              0));
      assertThatThrownBy(() -> coordinator.advance(ACTOR, command, false))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
      available.set(false);
      var cancelled = finish(coordinator, command, true);
      assertThat(cancelled.intent()).isEqualTo(first.intent());
      assertThat(cancelled.checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
      assertThat(scopes.getLast()).containsExactly(source.physicalModelId());
      assertThat(f.backend.calculation(source, ACTOR.tenantId(), 1, 30_000)).isNull();

      // A cancellation before any journal exists first releases its root-only attempt, then
      // freezes complete metadata under the complete scope. Subsequent recovery needs only root.
      available.set(true);
      current.set(frozenEdge);
      var unstarted = command(binding, 1);
      int before = scopes.size();
      coordinator.advance(ACTOR, unstarted, true);
      assertThat(deadlines.get(before)).isSameAs(deadlines.get(before + 1));
      assertThat(scopes.subList(before, scopes.size()))
          .containsExactly(
              Set.of(source.physicalModelId()),
              Set.of(
                  source.physicalModelId(),
                  binding.target().physicalModelId(),
                  binding.edge().physicalModelId()));
      available.set(false);
      assertThat(finish(coordinator, unstarted, true).checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);

      available.set(true);
      var published = finish(coordinator, command(binding, 2), false);
      assertThat(published.checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
      assertThat(f.backend.calculation(source, ACTOR.tenantId(), 3, 30_000).policy().definition())
          .isEqualTo(published.intent().command().calculation());
    }
  }

  private static RecordPublicationStore.Entry finish(
      RecordPublicationCoordinator coordinator,
      RecordWritePolicyPublicationCommand command,
      boolean cancel) {
    var entry = coordinator.advance(ACTOR, command, cancel);
    for (int i = 0; i < 8 && entry.checkpoint().progress() != null; i++) {
      entry = coordinator.advance(ACTOR, command, cancel);
    }
    assertThat(entry.checkpoint().progress()).isNull();
    return entry;
  }

  private static RecordPublicationMetadata.Snapshot snapshot(
      RecordCollectionBinding binding, Map<String, RecordPublicationIntent.Binding> edges) {
    var source = binding.source();
    return new RecordPublicationMetadata.Snapshot(
        Map.of(
            source.physicalModelId(),
            new RecordPublicationPatchCompiler.Resolved(source, 0, Map.of())),
        Map.of(source.physicalModelId(), frozen(source)),
        Map.of(source.physicalModelId(), Map.of()),
        Map.of(),
        edges);
  }

  private static RecordWritePolicyPublicationCommand command(
      RecordCollectionBinding binding, long expected) {
    var source = binding.source();
    var target = binding.target();
    var rule =
        new RecordCollectionRule(
            "1",
            binding.relationId(),
            binding.edge().physicalModelId(),
            frozen(binding.edge()).requestedSchema(),
            target.physicalModelId(),
            target.appId(),
            target.formId(),
            "target-page",
            null,
            null,
            null);
    return new RecordWritePolicyPublicationCommand(
        source.physicalModelId(),
        source.appId(),
        source.formId(),
        frozen(source).requestedSchema(),
        expected,
        expected + 1,
        "collection-" + expected,
        "a".repeat(64),
        Map.of(),
        30_000,
        Map.of(),
        new RecordCalculationDefinition(
            "1", Map.of(), Map.of(), null, null, null, null, null, List.of(rule)));
  }

  private static RecordPublicationIntent.Binding frozen(RuntimeRecordModel model) {
    var schema =
        new RecordSchemaReference(
            model.activeVersion(), CODEC.encode(model.activeSchema()).checksum());
    return new RecordPublicationIntent.Binding(schema, schema, model.target().metadataTarget(), 0);
  }
}
