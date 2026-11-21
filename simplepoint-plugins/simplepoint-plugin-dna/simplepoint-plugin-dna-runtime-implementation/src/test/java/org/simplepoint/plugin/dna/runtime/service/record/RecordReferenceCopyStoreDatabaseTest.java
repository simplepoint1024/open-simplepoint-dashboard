package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;

class RecordReferenceCopyStoreDatabaseTest {
  private final RecordReferenceCopyStore store = new RecordReferenceCopyStore();

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void ordinaryUnkeyedAndCalculatedEditsAdvanceOriginsAndRollbackTogether(boolean postgres)
      throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    var failLedger = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              CalciteUpdateRequest request = invocation.getArgument(0);
              var result = invocation.callRealMethod();
              if (request.sql().startsWith("UPDATE")
                  && request.sql().contains("simpoint_dna_reference_copies_v1")
                  && failLedger.compareAndSet(true, false)) {
                throw new IllegalStateException("Injected failure after ledger update");
              }
              return result;
            })
        .when(engine)
        .executeUpdate(any(), any());
    try (var f =
        new RecordReferenceIntegrityDatabaseTest.Fixture(
            postgres,
            false,
            true,
            engine,
            List.of(
                new org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition(
                    "note",
                    "note",
                    "Note",
                    org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.STRING,
                    true,
                    100,
                    null,
                    null,
                    null)))) {
      new RecordJournalProvisioner(f.schemaBackend).prepare(f.customer.target());
      var source = f.create(f.customer, Map.of("status", "Copied", "note", "original"));
      var key =
          new RecordReferenceCopyStore.Key(
              "tenant-a", f.customer.physicalModelId(), source.recordId());
      var base = RecordReferenceCopyStoreTest.origin();
      var origin =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin(
              base.relationId(),
              base.mappingId(),
              base.mode(),
              "note",
              base.mappingRevision(),
              base.targetModelId(),
              base.targetAppId(),
              base.targetFormId(),
              base.targetResourceCode(),
              base.targetRecordId(),
              base.targetVersion(),
              base.targetFieldId(),
              base.requirements());
      f.backend.executePreparedOwned(
          f.customer,
          List.of(),
          new RecordWriteAdmission("tenant-a", 0),
          30_000,
          operation(f),
          (reader, at, installed) ->
              prepared(source, new RecordReferenceCopyMutation(key, null, 0, List.of(origin))));
      installProtectedPolicy(f, 1, Map.of());
      var admission = new RecordWriteAdmission("tenant-a", 1);
      f.backend.execute(
          f.customer,
          admission,
          List.of(f.update(f.customer, source.recordId(), 0, Map.of("status", "Ordinary"))),
          30_000);
      assertThat(read(f, key)).isEqualTo(new RecordReferenceCopyStore.Stored(1, List.of(origin)));
      f.backend.execute(
          f.customer,
          admission,
          List.of(
              f.update(f.customer, source.recordId(), 1, Map.of("status", "Second")),
              f.update(f.customer, source.recordId(), 2, Map.of("status", "Third"))),
          30_000);
      assertThat(read(f, key)).isEqualTo(new RecordReferenceCopyStore.Stored(3, List.of(origin)));
      failLedger.set(true);
      assertThatThrownBy(
              () ->
                  f.backend.execute(
                      f.customer,
                      admission,
                      List.of(
                          f.update(f.customer, source.recordId(), 3, Map.of("status", "Rollback"))),
                      30_000))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(f.value(f.customer, source.recordId(), "status")).isEqualTo("Third");
      assertThat(read(f, key).sourceVersion()).isEqualTo(3);
      var installed = installProtectedPolicy(f, 2, Map.of("status", "UPPER([note])"));
      var resolver = org.mockito.Mockito.mock(RuntimeRecordModelResolver.class);
      org.mockito.Mockito.when(
              resolver.resolve(
                  f.access(f.customer).actor(),
                  f.customer.physicalModelId(),
                  installed.policy().parentSchema()))
          .thenReturn(f.customer);
      var calculations =
          new RecordCalculationWriteSupport(
              resolver,
              new org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec(
                  new com.fasterxml.jackson.databind.ObjectMapper()),
              f.compiler);
      var binding = calculations.bind(f.access(f.customer).actor(), f.customer, installed);
      var operation = operation(f);
      var result =
          f.backend.executePreparedOwned(
              f.customer,
              List.of(),
              new RecordWriteAdmission("tenant-a", 2),
              30_000,
              operation,
              (reader, at, policy) -> {
                binding.verify(policy);
                var write =
                    calculations.prepareSingle(
                        binding,
                        new RecordCalculationWriteSupport.Scope(f.customer, f.access(f.customer)),
                        Map.of(),
                        new org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation(
                            "edit", source.recordId(), 3, Map.of("note", "changed")),
                        source.recordId(),
                        reader,
                        at);
                return new RecordWritePreparation(
                    write.statements(), null, 1, write.references(), null, write.copies());
              });
      assertThat(result.affectedRows()).containsExactly(1);
      assertThat(f.value(f.customer, source.recordId(), "status")).isEqualTo("CHANGED");
      var proof = read(f, key);
      assertThat(proof.sourceVersion()).isEqualTo(4);
      assertThat(proof.origins()).containsExactly(origin);
      assertThat(proof.derivedOrigins())
          .singleElement()
          .satisfies(
              derived -> {
                assertThat(derived.destinationFieldId()).isEqualTo("status");
                assertThat(derived.calculationRevision()).isEqualTo(2);
                assertThat(derived.requirements()).containsExactlyElementsOf(origin.requirements());
              });
      assertThat(
              f.backend
                  .executePreparedOwned(
                      f.customer,
                      List.of(),
                      new RecordWriteAdmission("tenant-a", 2),
                      30_000,
                      operation,
                      (reader, at, policy) -> {
                        throw new AssertionError("Replay must not recompute committed origins");
                      })
                  .replayed())
          .isTrue();
      assertThat(read(f, key)).isEqualTo(proof);
    }
  }

  /** Privileged legacy fixture installation, not the public mapping publication path. */
  private static RecordCalculationStore.Installed installProtectedPolicy(
      RecordReferenceIntegrityDatabaseTest.Fixture f, long revision, Map<String, String> formulas) {
    var codec =
        new org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec(
            new com.fasterxml.jackson.databind.ObjectMapper());
    var policy =
        new RecordCalculationPolicy(
            1,
            f.customer.appId(),
            f.customer.formId(),
            new org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference(
                f.customer.requestedVersion(),
                codec.encode(f.customer.requestedSchema()).checksum()),
            new org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition(
                "1", formulas, Map.of()),
            Map.of(f.customer.physicalModelId(), java.util.Set.of("note", "status")));
    var parameters =
        new RecordCalculationStore()
            .insert(
                new RecordWriteFenceKey("tenant-a", f.customer.physicalModelId()),
                revision,
                "fixture",
                Instant.now(),
                policy)
            .parameters();
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(f.dataSource);
    var nativeParameters = parameters.toArray();
    for (int index : new int[] {4, 6}) {
      nativeParameters[index] =
          java.sql.Timestamp.from(
              java.time.LocalDateTime.parse((String) nativeParameters[index])
                  .toInstant(java.time.ZoneOffset.UTC));
    }
    jdbc.update(
        "INSERT INTO simpoint_dna_record_calculations_v1 (id, tenant_id, record_version,"
            + " created_by, created_at, updated_by, updated_at, model_id, policy_revision,"
            + " definition_checksum, definition_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        nativeParameters);
    assertThat(
            jdbc.update(
                "UPDATE simpoint_dna_record_write_fences_v1 SET write_revision = ?"
                    + " WHERE tenant_id = ? AND model_id = ?",
                revision,
                "tenant-a",
                f.customer.physicalModelId()))
        .isEqualTo(1);
    return new RecordCalculationStore.Installed(revision, policy);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void originLedgerCommitsAndReplaysWithTheBusinessRecordOnTheBorrowedTransaction(boolean postgres)
      throws Exception {
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, false, true)) {
      new RecordJournalProvisioner(f.schemaBackend).prepare(f.customer.target());
      var create = f.create(f.customer, Map.of("status", "Copied"));
      var key =
          new RecordReferenceCopyStore.Key(
              "tenant-a", f.customer.physicalModelId(), create.recordId());
      var origin = RecordReferenceCopyStoreTest.origin();
      var derived =
          new RecordDerivedCopyOrigin(
              1,
              "status",
              7,
              "b".repeat(64),
              List.of(
                  new RecordAggregateReadRequirement(
                      key.modelId(), key.recordId(), List.of("address"))),
              origin.requirements());
      var insert = new RecordReferenceCopyMutation(key, null, 0, List.of(origin), List.of(derived));
      var operation = operation(f);
      var result =
          f.backend.executePreparedOwned(
              f.customer,
              List.of(),
              new RecordWriteAdmission("tenant-a", 0),
              30_000,
              operation,
              (reader, at, policy) -> withSnapshot(f, create, insert));
      assertThat(result.replayed()).isFalse();
      assertThat(result.affectedRows()).containsExactly(1);
      assertThat(result.results()).hasSize(1);
      assertThat(read(f, key))
          .isEqualTo(new RecordReferenceCopyStore.Stored(0, List.of(origin), List.of(derived)));
      var captured = snapshot(f, operation);
      assertThat(captured.contractVersion()).isEqualTo(4);
      assertThat(captured.copyOrigins())
          .containsExactly(
              new RecordReferenceCopySnapshot(
                  null, create.recordId(), 0, List.of(origin), List.of(derived)));
      var missing =
          new RecordReferenceCopyStore.Key("tenant-a", key.modelId(), UUID.randomUUID().toString());
      var keys = List.of(missing, key);
      var batch =
          f.backend.read(
              f.customer,
              List.of(),
              f.access(f.customer),
              30_000,
              session -> store.readBatch(keys, session.query().apply(store.lookupBatch(keys))));
      assertThat(batch).containsOnlyKeys(key.recordId());
      assertThat(batch.get(key.recordId()))
          .isEqualTo(new RecordReferenceCopyStore.Stored(0, List.of(origin), List.of(derived)));
      assertThat(f.value(f.customer, create.recordId(), "status")).isEqualTo("Copied");
      var replay =
          f.backend.executePreparedOwned(
              f.customer,
              List.of(),
              new RecordWriteAdmission("tenant-a", 0),
              30_000,
              operation,
              (reader, at, policy) -> {
                throw new AssertionError("Committed copy must not be prepared again");
              });
      assertThat(replay.replayed()).isTrue();
      var update = f.update(f.customer, create.recordId(), 0, Map.of("status", "Cleared"));
      var clear = new RecordReferenceCopyMutation(key, read(f, key), 1, List.of(), List.of());
      f.backend.executePreparedOwned(
          f.customer,
          List.of(),
          new RecordWriteAdmission("tenant-a", 0),
          30_000,
          operation(f),
          (reader, at, policy) -> prepared(update, clear));
      assertThat(read(f, key)).isEqualTo(new RecordReferenceCopyStore.Stored(1, List.of()));
      assertThat(f.value(f.customer, create.recordId(), "status")).isEqualTo("Cleared");
      assertThat(snapshot(f, operation)).isEqualTo(captured);
      // Even an empty origin ledger is revisioned. A business-only update may not capture
      // silently stale provenance or leave a successful receipt behind.
      var missingLedgerUpdate =
          f.update(f.customer, create.recordId(), 1, Map.of("status", "Stale capture"));
      var missingLedgerOperation = operation(f);
      assertThatThrownBy(
              () ->
                  f.backend.executePreparedOwned(
                      f.customer,
                      List.of(),
                      new RecordWriteAdmission("tenant-a", 0),
                      30_000,
                      missingLedgerOperation,
                      (reader, at, policy) ->
                          new RecordWritePreparation(
                              List.of(missingLedgerUpdate), snapshotPlan(f, missingLedgerUpdate))))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
      assertThat(f.value(f.customer, create.recordId(), "status")).isEqualTo("Cleared");
      assertThat(f.backend.findResult(f.customer, missingLedgerOperation, 30_000)).isNull();
      assertThat(snapshot(f, missingLedgerOperation)).isNull();
      var staleUpdate =
          f.update(f.customer, create.recordId(), 1, Map.of("status", "Must rollback"));
      var staleLedger =
          new RecordReferenceCopyMutation(
              key, new RecordReferenceCopyStore.Stored(0, List.of(origin)), 2, List.of(origin));
      assertThatThrownBy(
              () ->
                  f.backend.executePreparedOwned(
                      f.customer,
                      List.of(),
                      new RecordWriteAdmission("tenant-a", 0),
                      30_000,
                      operation(f),
                      (reader, at, policy) -> prepared(staleUpdate, staleLedger)))
          .isInstanceOf(RecordMutationMissException.class);
      assertThat(f.value(f.customer, create.recordId(), "status")).isEqualTo("Cleared");
      assertThat(read(f, key)).isEqualTo(new RecordReferenceCopyStore.Stored(1, List.of()));
      assertThat(
              read(f, new RecordReferenceCopyStore.Key("tenant-b", key.modelId(), key.recordId())))
          .isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lateLedgerFailureRollsBackSourceOriginsAndOperationReceipt(boolean postgres)
      throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    var failed = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              CalciteUpdateRequest request = invocation.getArgument(0);
              var result = invocation.callRealMethod();
              if (request.sql().startsWith("INSERT INTO")
                  && request.sql().contains("simpoint_dna_reference_copies_v1")
                  && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("Injected failure after origin insert");
              }
              return result;
            })
        .when(engine)
        .executeUpdate(any(), any());
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, false, true, engine)) {
      new RecordJournalProvisioner(f.schemaBackend).prepare(f.customer.target());
      var create = f.create(f.customer, Map.of("status", "Must rollback"));
      var key =
          new RecordReferenceCopyStore.Key(
              "tenant-a", f.customer.physicalModelId(), create.recordId());
      var insert =
          new RecordReferenceCopyMutation(
              key, null, 0, List.of(RecordReferenceCopyStoreTest.origin()));
      var operation = operation(f);
      assertThatThrownBy(
              () ->
                  f.backend.executePreparedOwned(
                      f.customer,
                      List.of(),
                      new RecordWriteAdmission("tenant-a", 0),
                      30_000,
                      operation,
                      (reader, at, policy) -> prepared(create, insert)))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(failed.get()).isTrue();
      assertThat(f.count(f.customer)).isZero();
      assertThat(read(f, key)).isNull();
      assertThat(f.backend.findResult(f.customer, operation, 30_000)).isNull();
      // Constraint rejection is a different durable outcome: the successful business effects
      // still roll back, while the rejection survives to prevent revival of the same intent.
      var rejected = operation(f);
      assertThatThrownBy(
              () ->
                  f.backend.executePreparedOwned(
                      f.customer,
                      List.of(),
                      new RecordWriteAdmission("tenant-a", 0),
                      30_000,
                      rejected,
                      (reader, at, policy) ->
                          new RecordWritePreparation(
                              List.of(
                                  create,
                                  insert.statement(rejected, Instant.now()),
                                  insert.statement(rejected, Instant.now())),
                              null)))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
      assertThat(f.count(f.customer)).isZero();
      assertThat(read(f, key)).isNull();
      assertThatThrownBy(() -> f.backend.findResult(f.customer, rejected, 30_000))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED));
    }
  }

  private static RecordWritePreparation prepared(
      RecordSqlStatement source, RecordReferenceCopyMutation copy) {
    return new RecordWritePreparation(List.of(source), null, 1, List.of(), null, List.of(copy));
  }

  private static RecordWritePreparation withSnapshot(
      RecordReferenceIntegrityDatabaseTest.Fixture f,
      RecordSqlStatement source,
      RecordReferenceCopyMutation copy) {
    return new RecordWritePreparation(
        List.of(source), snapshotPlan(f, source), 1, List.of(), null, List.of(copy));
  }

  private static RecordSnapshotPlan snapshotPlan(
      RecordReferenceIntegrityDatabaseTest.Fixture f, RecordSqlStatement source) {
    var access = f.access(f.customer);
    var select =
        f.compiler.lookup(
            f.customer,
            access,
            new org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery(
                f.customer.physicalModelId(),
                new org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference(
                    f.customer.requestedVersion(), "a".repeat(64)),
                source.recordId(),
                List.of()));
    return new RecordSnapshotPlan(
        source.recordId(),
        source.resultVersion(),
        List.of(new RecordSnapshotPlan.Part(f.customer, access, select, true)),
        new RecordSnapshotValidation(f.customer, access, List.of()));
  }

  private RecordStoredSnapshot snapshot(
      RecordReferenceIntegrityDatabaseTest.Fixture f, RecordOperationIdentity operation) {
    return f.backend.read(
        f.customer,
        List.of(),
        f.access(f.customer),
        30_000,
        session -> session.snapshot(operation));
  }

  private RecordReferenceCopyStore.Stored read(
      RecordReferenceIntegrityDatabaseTest.Fixture f, RecordReferenceCopyStore.Key key) {
    var model = key.modelId().equals(f.trade.physicalModelId()) ? f.trade : f.customer;
    return f.backend.read(
        model,
        List.of(),
        f.access(model),
        30_000,
        session -> store.read(key, session.query().apply(store.lookup(key))));
  }

  private static RecordOperationIdentity operation(RecordReferenceIntegrityDatabaseTest.Fixture f) {
    return new RecordOperationIdentity(
        UUID.randomUUID().toString(),
        "tenant-a",
        "user",
        f.customer.physicalModelId(),
        UUID.randomUUID().toString(),
        "a".repeat(64));
  }
}
