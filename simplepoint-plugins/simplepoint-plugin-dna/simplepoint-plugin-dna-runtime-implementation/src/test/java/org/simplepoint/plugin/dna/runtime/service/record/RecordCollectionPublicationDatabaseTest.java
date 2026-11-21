package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/**
 * Real v3 journal and policy transactions; public authorization and control metadata are separate.
 */
class RecordCollectionPublicationDatabaseTest {
  private static final PhysicalSchemaCodec CODEC = new PhysicalSchemaCodec(new ObjectMapper());

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void commitsAndCancelsCollectionPoliciesWithoutScanningOrChangingEdges(boolean postgres)
      throws Exception {
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres)) {
      var binding = RecordCollectionSqlDatabaseTest.install(f);
      var source = f.create(binding.source(), Map.of());
      var target = f.create(binding.target(), Map.of());
      f.save(binding.source(), source);
      f.save(binding.target(), target);
      var edge =
          RecordCollectionEdgeKey.of(binding, "tenant-a", source.recordId(), target.recordId());
      RecordCollectionSqlDatabaseTest.execute(
          f,
          binding,
          edge,
          0,
          0,
          RecordCollectionEdgeState.absent(),
          CollectionEdgeMutation.Mode.LINK);
      new RecordJournalProvisioner(f.schemaBackend).prepare(binding.source().target());
      var batches = new RecordPublicationBatchExecutor(CODEC, f.compiler);
      var models =
          Map.of(
              binding.source().physicalModelId(),
              new RecordPublicationPatchCompiler.Resolved(binding.source(), 0, Map.of()));
      var fingerprints = new RecordRequestFingerprint(new ObjectMapper());
      var first =
          session(
              f,
              binding,
              s -> {
                var intent = freeze(binding, s, 0, null);
                return batches.start(
                    batches.protect(intent, models, null),
                    models,
                    s,
                    fingerprints.cancellationOf(intent.command()));
              });
      assertThat(first.intent().contractVersion()).isEqualTo(3);
      assertThat(first.checkpoint().total()).isEqualTo(1);
      var committed = finish(f, binding, batches, models, first);
      assertThat(committed.checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.COMMITTED);
      var store = new RecordCalculationStore();
      var installed =
          session(
              f,
              binding,
              s ->
                  store.read(
                      first.intent().key(), 1, s.read(store.lookup(first.intent().key(), 1))));
      assertThat(installed.policy().definition().collectionsOrEmpty()).hasSize(1);
      assertThat(installed.policy().definition()).isEqualTo(first.intent().command().calculation());
      var second =
          session(
              f,
              binding,
              s -> {
                var intent = batches.protect(freeze(binding, s, 1, installed), models, installed);
                batches.start(intent, models, s, fingerprints.cancellationOf(intent.command()));
                return batches.requestCancellation(
                    intent, fingerprints.cancellationOf(intent.command()), s);
              });
      var cancelled = finish(f, binding, batches, models, second);
      assertThat(cancelled.checkpoint().phase())
          .isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
      var restored =
          session(
              f,
              binding,
              s ->
                  store.read(
                      first.intent().key(), 2, s.read(store.lookup(first.intent().key(), 2))));
      assertThat(restored).isEqualTo(installed);
      assertThat(f.count(binding.edge())).isEqualTo(1);
      assertThat(((Number) f.value(binding.edge(), edge.id(), "record_version")).longValue())
          .isZero();
      assertThat(f.value(binding.edge(), edge.id(), binding.sourceColumn()))
          .isEqualTo(source.recordId());
      assertThat(f.value(binding.edge(), edge.id(), binding.targetColumn()))
          .isEqualTo(target.recordId());
      assertThat(
              ((Number) f.value(binding.source(), source.recordId(), "record_version")).longValue())
          .isEqualTo(1);
      assertThat(
              ((Number) f.value(binding.target(), target.recordId(), "record_version")).longValue())
          .isEqualTo(1);
    }
  }

  private static RecordPublicationStore.Entry finish(
      RecordReferenceIntegrityDatabaseTest.Fixture f,
      RecordCollectionBinding binding,
      RecordPublicationBatchExecutor batches,
      Map<String, RecordPublicationPatchCompiler.Resolved> models,
      RecordPublicationStore.Entry initial) {
    var entry = initial;
    for (int i = 0; i < 8 && entry.checkpoint().progress() != null; i++) {
      var previous = entry;
      entry =
          session(
              f,
              binding,
              s ->
                  batches.step(
                      previous.intent(),
                      models,
                      Map.of(),
                      previous.checkpoint().sequence(),
                      10,
                      new RecordRequestFingerprint(new ObjectMapper())
                          .cancellationOf(previous.intent().command()),
                      s));
      assertThat(entry.intent()).isEqualTo(initial.intent());
    }
    assertThat(entry.checkpoint().progress()).isNull();
    return entry;
  }

  private static RecordPublicationIntent freeze(
      RecordCollectionBinding binding,
      RecordPublicationSession session,
      long expected,
      RecordCalculationStore.Installed previous) {
    var source = binding.source();
    var target = binding.target();
    var rule =
        new RecordCollectionRule(
            "1",
            binding.relationId(),
            binding.edge().physicalModelId(),
            schema(binding.edge()),
            target.physicalModelId(),
            target.appId(),
            target.formId(),
            "target-page",
            null,
            null,
            null);
    var definition =
        new RecordCalculationDefinition(
            "1", Map.of(), Map.of(), null, null, null, null, null, List.of(rule));
    var command =
        new RecordWritePolicyPublicationCommand(
            source.physicalModelId(),
            source.appId(),
            source.formId(),
            schema(source),
            expected,
            expected + 1,
            "collection-" + expected,
            "a".repeat(64),
            Map.of(),
            30_000,
            Map.of(),
            definition);
    return new RecordPublicationIntent(
        3,
        new RecordWriteFenceKey("tenant-a", source.physicalModelId()),
        command,
        new RecordRequestFingerprint(new ObjectMapper()).of(command),
        session.admission().fingerprint(),
        "publisher",
        Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
        Map.of(source.physicalModelId(), frozen(source)),
        previous == null
            ? null
            : new RecordPublicationIntent.InstalledCalculation(
                previous.revision(), RecordCalculationStore.policyChecksum(previous.policy())),
        Map.of(source.physicalModelId(), Map.of()),
        Map.of(),
        Map.of(binding.relationId(), frozen(binding.edge())));
  }

  private static RecordPublicationIntent.Binding frozen(RuntimeRecordModel model) {
    return new RecordPublicationIntent.Binding(
        schema(model), schema(model), model.target().metadataTarget(), 0);
  }

  private static RecordSchemaReference schema(RuntimeRecordModel model) {
    return new RecordSchemaReference(
        model.activeVersion(), CODEC.encode(model.activeSchema()).checksum());
  }

  private static <T> T session(
      RecordReferenceIntegrityDatabaseTest.Fixture f,
      RecordCollectionBinding binding,
      Function<RecordPublicationSession, T> work) {
    return f.backend.publication(
        binding.source(),
        List.of(),
        new RecordWriteFenceKey("tenant-a", binding.source().physicalModelId()),
        30_000,
        work);
  }
}
