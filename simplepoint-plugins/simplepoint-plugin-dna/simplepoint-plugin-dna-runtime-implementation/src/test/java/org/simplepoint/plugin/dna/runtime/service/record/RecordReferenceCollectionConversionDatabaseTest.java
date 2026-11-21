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
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCollectionMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCollectionPreflightQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RecordReferenceCollectionConversionDatabaseTest {
  private static final PhysicalSchemaCodec CODEC = new PhysicalSchemaCodec(new ObjectMapper());
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant-a", "publisher");

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void inventoriesExactTenantReferencesInStableBatchesWithoutWriting(boolean postgres)
      throws Exception {
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, true)) {
      var binding = RecordCollectionSqlDatabaseTest.install(f, true);
      var model = binding.source();
      var target = f.create(model, Map.of());
      var empty = f.create(model, Map.of());
      f.save(model, target, empty);
      var first = f.create(model, Map.of("customer_id", target.recordId()));
      var second = f.create(model, Map.of("customer_id", target.recordId()));
      var historical = f.create(model, Map.of("customer_id", target.recordId()));
      f.save(model, first, second, historical);
      f.save(model, f.delete(model, historical.recordId(), 0));

      var rule =
          new RecordReferenceRule(
              "1",
              "self-customer",
              new RecordValidationField("parent", "customer"),
              model.physicalModelId(),
              model.appId(),
              model.formId(),
              "target-page",
              null,
              null);
      var key = new RecordWriteFenceKey("tenant-a", model.physicalModelId());
      var edgeRule =
          new RecordCollectionRule(
              "1",
              binding.relationId(),
              binding.edge().physicalModelId(),
              schema(binding.edge()),
              model.physicalModelId(),
              model.appId(),
              model.formId(),
              "target-page",
              null,
              null,
              null);
      var query =
          new RecordReferenceCollectionPreflightQuery(
              model.physicalModelId(),
              model.appId(),
              model.formId(),
              schema(model),
              1,
              rule.relationId(),
              edgeRule);
      var coordinator = preparePublication(f, binding, query, key);
      assertThatThrownBy(() -> coordinator.inspectReferenceCollection(ACTOR, query))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT));
      installSourcePolicy(f, model, rule, key);
      assertThat(coordinator.inspectReferenceCollection(ACTOR, query)).isEqualTo(3);

      var conversion = new RecordReferenceCollectionConversion(key, rule, binding);
      f.backend.publication(
          model,
          List.of(binding.edge()),
          key,
          30_000,
          session -> {
            assertThat(conversion.inspect(session)).isEqualTo(3);
            var one = conversion.page(null, 2, session);
            assertThat(one).hasSize(2);
            var two = conversion.page(one.getLast().key().sourceId(), 2, session);
            assertThat(two).hasSize(1);
            var expected =
                Stream.of(first.recordId(), second.recordId(), historical.recordId())
                    .sorted()
                    .map(
                        id ->
                            new RecordReferenceCollectionConversion.Entry(
                                RecordCollectionEdgeKey.of(
                                    binding, key.tenantId(), id, target.recordId()),
                                id.equals(historical.recordId()) ? 1 : 0))
                    .toList();
            assertThat(Stream.concat(one.stream(), two.stream()).toList()).isEqualTo(expected);
            assertThat(conversion.page(two.getLast().key().sourceId(), 2, session)).isEmpty();
            return null;
          });
      assertThat(f.count(binding.edge())).isZero();
      var other = new RecordWriteFenceKey("tenant-b", key.modelId());
      f.backend.publication(
          model,
          List.of(binding.edge()),
          other,
          30_000,
          session -> {
            var foreign = new RecordReferenceCollectionConversion(other, rule, binding);
            assertThat(foreign.inspect(session)).isZero();
            assertThat(foreign.page(null, 10, session)).isEmpty();
            return null;
          });

      var existing =
          RecordCollectionEdgeKey.of(binding, key.tenantId(), first.recordId(), target.recordId());
      var link =
          RecordCollectionSql.change(
              binding,
              existing,
              RecordCollectionEdgeState.absent(),
              new CollectionEdgeMutation(
                  existing.targetId(), 0L, -1L, CollectionEdgeMutation.Mode.LINK),
              f.access(model),
              Instant.now());
      f.backend.publication(
          model,
          List.of(binding.edge()),
          key,
          30_000,
          session -> {
            session.lockRows(
                new RecordPublicationLockPlan(
                    List.of(link),
                    List.of(
                        new RecordReferenceCheck(
                            "left",
                            binding.edge().physicalModelId(),
                            existing.id(),
                            existing.sourceId(),
                            false),
                        new RecordReferenceCheck(
                            "right",
                            binding.edge().physicalModelId(),
                            existing.id(),
                            existing.targetId(),
                            false)),
                    () -> {}));
            session.write(link);
            return null;
          });
      assertThatThrownBy(() -> coordinator.inspectReferenceCollection(ACTOR, query))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE));
      assertThat(f.count(binding.edge())).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restoresACommittedBatchAndThenConvertsAndActivatesTheReplacement(boolean postgres)
      throws Exception {
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, true)) {
      var binding = RecordCollectionSqlDatabaseTest.install(f, true);
      var model = binding.source();
      var target = f.create(model, Map.of());
      f.save(model, target);
      var sources =
          java.util.stream.IntStream.range(0, 12)
              .mapToObj(i -> f.create(model, Map.of("customer_id", target.recordId())))
              .toList();
      f.save(model, sources.toArray(RecordSqlStatement[]::new));
      var historical = f.create(model, Map.of("customer_id", target.recordId()));
      f.save(model, historical);
      f.save(model, f.delete(model, historical.recordId(), 0));
      var source =
          new RecordReferenceRule(
              "1",
              "self-customer",
              new RecordValidationField("parent", "customer"),
              model.physicalModelId(),
              model.appId(),
              model.formId(),
              "old-target",
              null,
              null);
      var key = new RecordWriteFenceKey("tenant-a", model.physicalModelId());
      new RecordJournalProvisioner(f.schemaBackend).prepare(model.target());
      installSourcePolicy(f, model, source, key);
      var old = f.backend.calculation(model, key.tenantId(), 1, 30_000);
      var candidateAvailable = new AtomicBoolean(true);
      var failBeforeCommit = new AtomicBoolean(true);
      var coordinator = migrationCoordinator(f, binding, candidateAvailable, failBeforeCommit);
      var command = migrationCommand(binding, source, 1);
      var entry = coordinator.advance(ACTOR, command, false);
      assertThat(entry.intent().contractVersion()).isEqualTo(5);
      assertThat(entry.intent().conversion().sourceCount()).isEqualTo(12);
      for (int i = 0;
          i < 8 && entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.STAGING;
          i++) {
        entry = coordinator.advance(ACTOR, command, false);
      }
      assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.APPLYING);
      assertThatThrownBy(() -> coordinator.advance(ACTOR, command, false))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED));
      assertThat(f.count(binding.edge())).isZero();
      for (var row : sources) {
        assertThat(f.value(model, row.recordId(), "customer_id")).isEqualTo(target.recordId());
      }
      entry = coordinator.advance(ACTOR, command, false);
      assertThat(entry.checkpoint().applied()).isBetween(1L, 12L);
      assertThat(f.count(binding.edge())).isBetween(1L, 10L);
      assertThatThrownBy(() -> f.backend.calculation(model, key.tenantId(), 1, 30_000))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS));
      candidateAvailable.set(false);
      // A fresh coordinator reloads the stage from the target database without the live entrance.
      var restarted = migrationCoordinator(f, binding, candidateAvailable, failBeforeCommit);
      var cancelled = finish(restarted, command, true);
      assertThat(cancelled.checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
      assertThat(f.backend.calculation(model, key.tenantId(), 2, 30_000)).isEqualTo(old);
      assertThat(f.count(binding.edge())).isZero();
      for (var row : sources) {
        assertThat(f.value(model, row.recordId(), "customer_id")).isEqualTo(target.recordId());
        assertThat(((Number) f.value(model, row.recordId(), "record_version")).longValue())
            .isZero();
        assertThat(f.value(model, row.recordId(), "updated_by")).isEqualTo("user");
      }

      candidateAvailable.set(true);
      var retry = migrationCommand(binding, source, 2);
      var published = finish(restarted, retry, false);
      assertThat(published.checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
      var installed = f.backend.calculation(model, key.tenantId(), 3, 30_000);
      assertThat(installed.policy().definition().referencesOrEmpty())
          .containsExactly(retry.conversionSource());
      assertThat(installed.policy().definition().collectionsOrEmpty())
          .containsExactly(retry.conversionDestination());
      var rules =
          new RecordReferenceRules(
              model,
              Map.of(),
              installed.policy().definition(),
              Map.of("parent", Map.of("customer", model.activeField("customer_id"))));
      assertThatThrownBy(
              () ->
                  rules.checks(
                      "parent",
                      sources.getFirst().recordId(),
                      Map.of(),
                      Map.of("customer_id", target.recordId()),
                      Map.of()))
          .isInstanceOf(DnaRuntimeException.class);
      var rollback =
          new RecordWritePolicyPublicationCommand(
              model.physicalModelId(),
              model.appId(),
              model.formId(),
              schema(model),
              3,
              4,
              "old-page-publication",
              "b".repeat(64),
              Map.of(),
              30_000,
              Map.of(),
              old.policy().definition());
      assertThatThrownBy(
              () -> RecordReferenceCollectionConversion.requireTransition(rollback, installed))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
      for (var row : sources) {
        var edge =
            RecordCollectionEdgeKey.of(binding, key.tenantId(), row.recordId(), target.recordId());
        assertThat(f.value(model, row.recordId(), "customer_id")).isNull();
        assertThat(((Number) f.value(model, row.recordId(), "record_version")).longValue())
            .isEqualTo(1);
        assertThat(f.value(binding.edge(), edge.id(), binding.targetColumn()))
            .isEqualTo(target.recordId());
        assertThat(f.value(binding.edge(), edge.id(), "created_org_dept_id"))
            .isEqualTo("department");
      }
      assertThat(f.count(binding.edge())).isEqualTo(12);
      assertThat(f.value(model, historical.recordId(), "customer_id")).isEqualTo(target.recordId());
      assertThat(f.value(model, historical.recordId(), "updated_by")).isEqualTo("user");
      candidateAvailable.set(false);
      assertThat(restarted.advance(ACTOR, retry, false)).isEqualTo(published);
    }
  }

  private static RecordPublicationCoordinator migrationCoordinator(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      AtomicBoolean candidateAvailable,
      AtomicBoolean failBeforeCommit) {
    var root = binding.source();
    var edge = freeze(binding.edge());
    var target = freeze(binding.target());
    var entrance =
        new RecordPublicationIntent.Resource(
            "binding",
            new RecordPublicationIntent.Projection(
                "projection", "owner", 1, binding.target().physicalModelId()));
    var metadata = mock(RecordPublicationMetadata.class);
    when(metadata.resolve(eq(ACTOR), any(), any(), anyBoolean(), anyBoolean()))
        .thenAnswer(
            call -> {
              boolean validate = call.getArgument(3);
              boolean conversion = call.getArgument(4);
              if (validate && !candidateAvailable.get()) {
                throw RecordReferenceAdmission.incompatible();
              }
              return new RecordPublicationMetadata.Snapshot(
                  Map.of(
                      root.physicalModelId(),
                      new RecordPublicationPatchCompiler.Resolved(root, 0, Map.of())),
                  Map.of(root.physicalModelId(), freeze(root)),
                  Map.of(root.physicalModelId(), Map.of()),
                  Map.of(),
                  validate ? Map.of(binding.relationId(), edge) : Map.of(),
                  Map.of(),
                  Map.of(),
                  conversion
                      ? new RecordPublicationMetadata.Conversion(
                          binding, edge, target, validate ? entrance : null)
                      : null);
            });
    var control = mock(RecordPublicationControlFence.class);
    when(control.execute(eq(ACTOR.tenantId()), any(), any(RecordExecutionDeadline.class), any()))
        .thenAnswer(
            call -> {
              Function<Map<String, PhysicalModelEntity>, Object> work = call.getArgument(3);
              return work.apply(Map.of());
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
                    if (result instanceof RecordPublicationStore.Entry step
                        && step.checkpoint().phase() == RecordPublicationCheckpoint.Phase.APPLYING
                        && step.checkpoint().applied() > 0
                        && failBeforeCommit.compareAndSet(true, false)) {
                      throw new DnaRuntimeException(
                          DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
                          "Injected interruption after the business writes and checkpoint");
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

  private static RecordPublicationStore.Entry finish(
      RecordPublicationCoordinator coordinator,
      RecordWritePolicyPublicationCommand command,
      boolean cancel) {
    var entry = coordinator.advance(ACTOR, command, cancel);
    for (int i = 0; i < 12 && entry.checkpoint().progress() != null; i++) {
      entry = coordinator.advance(ACTOR, command, cancel);
    }
    return entry;
  }

  private static RecordWritePolicyPublicationCommand migrationCommand(
      RecordCollectionBinding binding, RecordReferenceRule source, long expected) {
    var retired =
        new RecordReferenceRule(
            "2",
            source.relationId(),
            source.source(),
            source.targetModelId(),
            source.targetAppId(),
            source.targetFormId(),
            source.targetResourceCode(),
            null,
            null,
            null,
            List.of(),
            true);
    var edge =
        new RecordCollectionRule(
            "1",
            binding.relationId(),
            binding.edge().physicalModelId(),
            schema(binding.edge()),
            source.targetModelId(),
            source.targetAppId(),
            source.targetFormId(),
            "target-page",
            null,
            null,
            null);
    var definition =
        new RecordCalculationDefinition(
            "1", Map.of(), Map.of(), null, null, null, null, List.of(retired), List.of(edge));
    var model = binding.source();
    return new RecordWritePolicyPublicationCommand(
        model.physicalModelId(),
        model.appId(),
        model.formId(),
        schema(model),
        expected,
        expected + 1,
        "conversion-" + expected,
        "a".repeat(64),
        Map.of(),
        30_000,
        Map.of(),
        definition,
        List.of(),
        new RecordReferenceCollectionMigration(source.relationId(), edge.relationId()));
  }

  private static RecordPublicationIntent.Binding freeze(RuntimeRecordModel model) {
    var schema = schema(model);
    return new RecordPublicationIntent.Binding(schema, schema, model.target().metadataTarget(), 0);
  }

  private static RecordPublicationCoordinator preparePublication(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      RecordReferenceCollectionPreflightQuery query,
      RecordWriteFenceKey key) {
    new RecordJournalProvisioner(fixture.schemaBackend).prepare(binding.source().target());
    var control = mock(RecordPublicationControlFence.class);
    var metadata = mock(RecordPublicationMetadata.class);
    when(control.execute(eq(ACTOR.tenantId()), any(), any(RecordExecutionDeadline.class), any()))
        .thenAnswer(
            call -> {
              assertThat(Set.copyOf(call.<Collection<String>>getArgument(1)))
                  .containsExactlyInAnyOrder(key.modelId(), binding.edge().physicalModelId());
              Function<Map<String, PhysicalModelEntity>, Long> work = call.getArgument(3);
              return work.apply(Map.of());
            });
    when(metadata.resolveConversion(ACTOR, query)).thenReturn(binding);
    return new RecordPublicationCoordinator(
        control,
        metadata,
        fixture.backend,
        new RecordPublicationBatchExecutor(CODEC, fixture.compiler),
        new RecordRequestFingerprint(new ObjectMapper()));
  }

  private static void installSourcePolicy(
      RecordReferenceIntegrityDatabaseTest.Fixture f,
      RuntimeRecordModel model,
      RecordReferenceRule rule,
      RecordWriteFenceKey key) {
    var definition =
        new RecordCalculationDefinition(
            "1", Map.of(), Map.of(), null, null, null, null, List.of(rule));
    var schema = schema(model);
    var command =
        new RecordWritePolicyPublicationCommand(
            model.physicalModelId(),
            model.appId(),
            model.formId(),
            schema,
            0,
            1,
            "before-conversion",
            "a".repeat(64),
            Map.of(),
            30_000,
            Map.of(),
            definition);
    f.backend.publication(
        model,
        List.of(),
        key,
        30_000,
        session -> {
          var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          var intent =
              new RecordPublicationIntent(
                  2,
                  key,
                  command,
                  new RecordRequestFingerprint(new ObjectMapper()).of(command),
                  session.admission().fingerprint(),
                  ACTOR.actorId(),
                  now.toString(),
                  Map.of(
                      key.modelId(),
                      new RecordPublicationIntent.Binding(
                          schema, schema, model.target().metadataTarget(), 0)),
                  null,
                  Map.of(key.modelId(), Map.of()));
          session.write(
              new RecordCalculationStore()
                  .insert(
                      key,
                      1,
                      ACTOR.actorId(),
                      now,
                      new RecordCalculationPolicy(
                          1, model.appId(), model.formId(), schema, definition)));
          session.activate(intent);
          return null;
        });
  }

  private static RecordSchemaReference schema(RuntimeRecordModel model) {
    return new RecordSchemaReference(
        model.activeVersion(), CODEC.encode(model.activeSchema()).checksum());
  }
}
