package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Physical ledger atomicity; public collection authorization and retry admission are separate. */
class RecordCollectionJournalDatabaseTest {
  private final RecordCollectionJournal journal = new RecordCollectionJournal();

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void maximumBatchPreservesEveryEndpointEdgeAndReceiptInOneTransaction(boolean postgres)
      throws Exception {
    try (var fixture = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres)) {
      var binding = RecordCollectionSqlDatabaseTest.install(fixture);
      new RecordJournalProvisioner(fixture.schemaBackend).prepare(binding.source().target());
      var source = fixture.create(binding.source(), Map.of());
      fixture.save(binding.source(), source);
      var targets =
          java.util.stream.IntStream.range(0, 100)
              .mapToObj(ignored -> fixture.create(binding.target(), Map.of()))
              .toList();
      fixture.backend.execute(
          binding.target(), new RecordWriteAdmission("tenant-a", 0), targets, 30_000);
      var command =
          new CollectionRecordCommand(
              "1",
              binding.source().physicalModelId(),
              new RecordSchemaReference(
                  1,
                  new PhysicalSchemaCodec(new ObjectMapper())
                      .encode(binding.source().activeSchema())
                      .checksum()),
              "members",
              source.recordId(),
              0L,
              targets.stream()
                  .map(
                      target ->
                          new CollectionEdgeMutation(
                              target.recordId(), 0L, -1L, CollectionEdgeMutation.Mode.LINK))
                  .toList(),
              "maximum-collection",
              30_000);
      var access = fixture.access(binding.source());
      var operation =
          RecordOperationIdentity.of(
              access.actor(),
              command,
              new RecordRequestFingerprint(new ObjectMapper()).of(command));
      var planned =
          RecordCollectionMutationPlan.prepare(
              binding,
              command,
              access,
              fixture.access(binding.target()),
              observe(fixture, binding, command, false, false),
              Instant.now().truncatedTo(ChronoUnit.MICROS),
              fixture.compiler);
      assertThat(planned.statements()).hasSize(201);
      assertThat(planned.result().edges()).hasSize(100);
      var receipt = journal.insert(operation, command, binding, planned.result());
      assertThatThrownBy(() -> transact(fixture, binding, planned.statements(), receipt, true))
          .isInstanceOf(DnaRuntimeException.class);
      var rolledBack = observe(fixture, binding, command, false, false);
      assertThat(rolledBack.sourceVersion()).isZero();
      assertThat(rolledBack.targetVersions().values()).containsOnly(0L);
      assertThat(rolledBack.edges().values()).containsOnly(RecordCollectionEdgeState.absent());
      assertThat(
              journal.read(
                  operation,
                  command,
                  fixture.backend.query(binding.source(), journal.lookup(operation), 30_000)))
          .isNull();
      transact(fixture, binding, planned.statements(), receipt, false);
      var committed = observe(fixture, binding, command, false, false);
      assertThat(committed.sourceVersion()).isEqualTo(1);
      assertThat(committed.targetVersions()).hasSize(100);
      assertThat(committed.targetVersions().values()).containsOnly(1L);
      assertThat(committed.edges()).hasSize(100);
      assertThat(committed.edges().values()).containsOnly(new RecordCollectionEdgeState(0, true));
      assertThat(
              journal.read(
                  operation,
                  command,
                  fixture.backend.query(binding.source(), journal.lookup(operation), 30_000)))
          .isEqualTo(planned.result());
      assertThat(
              fixture.backend.findCollectionResult(
                  binding.source(), access, operation, command, 30_000))
          .isEqualTo(planned.result());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void collectionReceiptRollsBackWithBothEndpointsAndReplaysAfterLaterChanges(boolean postgres)
      throws Exception {
    try (var fixture = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres)) {
      var binding = RecordCollectionSqlDatabaseTest.install(fixture);
      new RecordJournalProvisioner(fixture.schemaBackend).prepare(binding.source().target());
      var source = fixture.create(binding.source(), Map.of());
      var target = fixture.create(binding.target(), Map.of());
      fixture.save(binding.source(), source);
      fixture.save(binding.target(), target);
      var access = fixture.access(binding.source());
      var key =
          RecordCollectionEdgeKey.of(
              binding, access.actor().tenantId(), source.recordId(), target.recordId());
      Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
      var mutation =
          new CollectionEdgeMutation(key.targetId(), 0L, -1L, CollectionEdgeMutation.Mode.LINK);
      var schema =
          new PhysicalSchemaCodec(new ObjectMapper()).encode(binding.source().activeSchema());
      var command =
          new CollectionRecordCommand(
              "1",
              binding.source().physicalModelId(),
              new RecordSchemaReference(1, schema.checksum()),
              "members",
              key.sourceId(),
              0L,
              List.of(mutation),
              "atomic-collection",
              30_000);
      var operation =
          RecordOperationIdentity.of(
              access.actor(),
              command,
              new RecordRequestFingerprint(new ObjectMapper()).of(command));
      var result =
          new CollectionRecordResult(
              "1",
              key.sourceId(),
              1L,
              List.of(new CollectionRecordResult.Edge(key.targetId(), 1L, 0L, true, true)),
              at);
      var planned =
          RecordCollectionMutationPlan.prepare(
              binding,
              command,
              access,
              fixture.access(binding.target()),
              observe(fixture, binding, command, false, false),
              at,
              fixture.compiler);
      assertThat(planned.result()).isEqualTo(result);
      for (boolean restrictSource : List.of(false, true)) {
        assertThatThrownBy(
                () -> observe(fixture, binding, command, restrictSource, !restrictSource))
            .isInstanceOfSatisfying(
                DnaRuntimeException.class,
                error ->
                    assertThat(error.errorCode())
                        .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
      }
      assertThat(
              fixture.backend.findCollectionResult(
                  binding.source(), access, operation, command, 30_000))
          .isNull();
      var statements = planned.statements();
      var receipt = journal.insert(operation, command, binding, result);
      assertThatThrownBy(() -> transact(fixture, binding, statements, receipt, true))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(
              ((Number) fixture.value(binding.source(), key.sourceId(), "record_version"))
                  .longValue())
          .isZero();
      assertThat(
              ((Number) fixture.value(binding.target(), key.targetId(), "record_version"))
                  .longValue())
          .isZero();
      assertThat(fixture.count(binding.edge())).isZero();
      assertThat(
              journal.read(
                  operation,
                  command,
                  fixture.backend.query(binding.source(), journal.lookup(operation), 30_000)))
          .isNull();

      transact(fixture, binding, statements, receipt, false);
      assertThat(fixture.count(binding.edge())).isEqualTo(1);
      // Later unlink advances both versions, while the original response remains immutable.
      RecordCollectionSqlDatabaseTest.execute(
          fixture,
          binding,
          key,
          1,
          1,
          new RecordCollectionEdgeState(0, true),
          CollectionEdgeMutation.Mode.UNLINK);
      assertThat(
              journal.read(
                  operation,
                  command,
                  fixture.backend.query(binding.source(), journal.lookup(operation), 30_000)))
          .isEqualTo(result);
      assertThat(
              fixture.backend.findCollectionResult(
                  binding.source(), access, operation, command, 30_000))
          .isEqualTo(result);
      assertThat(
              ((Number) fixture.value(binding.source(), key.sourceId(), "record_version"))
                  .longValue())
          .isEqualTo(2);
      assertThat(
              ((Number) fixture.value(binding.target(), key.targetId(), "record_version"))
                  .longValue())
          .isEqualTo(2);

      // An unchanged unlink is durable without fabricating a source, target or edge revision.
      var noop =
          new CollectionRecordCommand(
              "1",
              command.sourceModelId(),
              command.sourceSchema(),
              command.relationId(),
              key.sourceId(),
              2L,
              List.of(
                  new CollectionEdgeMutation(
                      key.targetId(), 2L, 1L, CollectionEdgeMutation.Mode.UNLINK)),
              "unchanged-collection",
              30_000);
      var noopIdentity =
          RecordOperationIdentity.of(
              access.actor(), noop, new RecordRequestFingerprint(new ObjectMapper()).of(noop));
      var noopResult =
          new CollectionRecordResult(
              "1",
              key.sourceId(),
              2L,
              List.of(new CollectionRecordResult.Edge(key.targetId(), 2L, 1L, false, false)),
              at);
      var noopPlan =
          RecordCollectionMutationPlan.prepare(
              binding,
              noop,
              access,
              fixture.access(binding.target()),
              observe(fixture, binding, noop, false, false),
              at,
              fixture.compiler);
      assertThat(noopPlan.statements()).isEmpty();
      assertThat(noopPlan.result()).isEqualTo(noopResult);
      transact(
          fixture,
          binding,
          List.of(),
          journal.insert(noopIdentity, noop, binding, noopResult),
          false);
      assertThat(
              journal.read(
                  noopIdentity,
                  noop,
                  fixture.backend.query(binding.source(), journal.lookup(noopIdentity), 30_000)))
          .isEqualTo(noopResult);
      assertThat(((Number) fixture.value(binding.edge(), key.id(), "record_version")).longValue())
          .isEqualTo(1);
      assertThat(
              ((Number) fixture.value(binding.source(), key.sourceId(), "record_version"))
                  .longValue())
          .isEqualTo(2);
      assertThat(
              ((Number) fixture.value(binding.target(), key.targetId(), "record_version"))
                  .longValue())
          .isEqualTo(2);
    }
  }

  private static RecordCollectionMutationPlan.Observations observe(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      CollectionRecordCommand command,
      boolean restrictSource,
      boolean restrictTarget) {
    var rule =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule(
            "1",
            binding.relationId(),
            binding.edge().physicalModelId(),
            new RecordSchemaReference(
                1,
                new PhysicalSchemaCodec(new ObjectMapper())
                    .encode(binding.edge().activeSchema())
                    .checksum()),
            binding.target().physicalModelId(),
            binding.target().appId(),
            binding.target().formId(),
            "test-resource",
            null,
            null,
            null);
    var sourceAccess = fixture.access(binding.source());
    var targetAccess = fixture.access(binding.target());
    var reader =
        new RecordCollectionObservationReader(
            binding,
            command,
            rule,
            restrictSource
                ? RecordCollectionSqlDatabaseTest.restricted(sourceAccess)
                : sourceAccess,
            restrictTarget
                ? RecordCollectionSqlDatabaseTest.restricted(targetAccess)
                : targetAccess,
            fixture.compiler);
    return fixture.backend.publication(
        binding.source(),
        List.of(binding.target(), binding.edge()),
        new RecordWriteFenceKey("tenant-a", binding.source().physicalModelId()),
        30_000,
        session -> reader.read(session::read));
  }

  private static void transact(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      List<RecordSqlStatement> statements,
      RecordSqlStatement receipt,
      boolean fail) {
    fixture.backend.publication(
        binding.source(),
        List.of(binding.target(), binding.edge()),
        new RecordWriteFenceKey("tenant-a", binding.source().physicalModelId()),
        30_000,
        session -> {
          session.lockRows(new RecordPublicationLockPlan(statements, List.of(), () -> {}));
          statements.forEach(session::write);
          session.write(receipt);
          if (fail) {
            throw new DnaRuntimeException(
                DnaRuntimeErrorCode.PERSISTENCE_FAILED,
                "Injected failure after collection receipt insertion");
          }
          return null;
        });
  }
}
