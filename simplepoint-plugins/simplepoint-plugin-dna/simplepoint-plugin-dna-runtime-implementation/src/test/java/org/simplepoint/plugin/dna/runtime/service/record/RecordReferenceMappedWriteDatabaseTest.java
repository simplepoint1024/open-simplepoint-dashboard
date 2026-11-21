package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingConfirmation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Real mapped values, calculation, version qualification, receipt and proof on PG/MySQL. */
class RecordReferenceMappedWriteDatabaseTest {
  @AfterEach
  void cleanupContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void copiedValueAndDerivedProofCommitTogetherAndReplayCannotRecompute(boolean postgres)
      throws Exception {
    try (var f = fixture(postgres)) {
      var target = f.customer("hello");
      var input = input(f, target);
      var result = execute(f, input);
      assertThat(result.affectedRows()).containsExactly(1);
      assertThat(f.base.value(f.base.trade, input.rowId(), "note")).isEqualTo("hello");
      assertThat(f.base.value(f.base.trade, input.rowId(), "status")).isEqualTo("HELLO");
      var proof = proof(f, input.rowId());
      assertThat(proof.origins())
          .singleElement()
          .satisfies(
              origin -> {
                assertThat(origin.targetRecordId()).isEqualTo(target);
                assertThat(origin.targetVersion()).isZero();
              });
      assertThat(proof.derivedOrigins())
          .singleElement()
          .satisfies(origin -> assertThat(origin.destinationFieldId()).isEqualTo("status"));
      var replay =
          f.base.backend.executePreparedOwned(
              f.base.trade,
              List.of(),
              new RecordWriteAdmission("tenant-a", 1),
              30000,
              input.operation(),
              (reader, at, policy) -> {
                throw new AssertionError("Receipt must precede preparation");
              });
      assertThat(replay.replayed()).isTrue();
      assertThat(proof(f, input.rowId())).isEqualTo(proof);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void concurrentTargetChangeRejectsTheEntireBusinessAndProofWrite(boolean postgres)
      throws Exception {
    try (var f = fixture(postgres)) {
      var target = f.customer("old");
      var input = input(f, target);
      f.base.save(
          f.base.customer, f.base.update(f.base.customer, target, 0, Map.of("status", "new")));
      assertThatThrownBy(() -> execute(f, input))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
      assertThat(f.base.count(f.base.trade)).isZero();
      assertThat(proof(f, input.rowId())).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void lateProofFailureRollsBackValuesCalculationAndReceiptTogether(boolean postgres)
      throws Exception {
    var engine = org.mockito.Mockito.spy(new DefaultCalciteQueryEngine());
    var failed = new java.util.concurrent.atomic.AtomicBoolean(false);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              org.simplepoint.data.calcite.core.query.CalciteUpdateRequest request =
                  invocation.getArgument(0);
              var result = invocation.callRealMethod();
              if (request.sql().startsWith("INSERT INTO")
                  && request.sql().contains("simpoint_dna_reference_copies_v1")
                  && failed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                    "Injected mapped proof failure after physical insert");
              }
              return result;
            })
        .when(engine)
        .executeUpdate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    try (var f = fixture(postgres, engine)) {
      var input = input(f, f.customer("rollback"));
      assertThatThrownBy(() -> execute(f, input)).isInstanceOf(DnaRuntimeException.class);
      assertThat(failed.get()).isTrue();
      assertThat(f.base.count(f.base.trade)).isZero();
      assertThat(proof(f, input.rowId())).isNull();
      assertThat(f.base.backend.findResult(f.base.trade, input.operation(), 30000)).isNull();
      assertThat(execute(f, input).affectedRows()).containsExactly(1);
      assertThat(f.base.value(f.base.trade, input.rowId(), "status")).isEqualTo("ROLLBACK");
      assertThat(proof(f, input.rowId()).derivedOrigins()).hasSize(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void servicePreviewSaveAndRecoveryUseTheCompleteMappedIntent(boolean postgres) throws Exception {
    try (var f = fixture(postgres)) {
      var input = input(f, f.customer("service"));
      var request = input.command();
      var selectedTarget = request.confirmations().getFirst().observation().targetRecordId();
      var query =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery(
              "1",
              f.base.trade.physicalModelId(),
              f.schema,
              1,
              "trade-customer",
              selectedTarget,
              List.of(),
              30000);
      var observation = f.ownedService.observeMapping(query);
      assertThat(observation.observation())
          .isEqualTo(request.confirmations().getFirst().observation());
      assertThat(observation.snapshots())
          .containsEntry(
              "note",
              new org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue(
                  org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind.STRING,
                  "service"));
      assertThat(observation.defaults()).isEmpty();
      var command =
          new MappedOwnedRecordCommand(
              "1",
              new OwnedRecordCommand(
                  request.command().parent(),
                  request.command().details(),
                  new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSnapshotRequest(
                      List.of())),
              null,
              request.confirmations());
      var preview = f.ownedService.previewMapped(request);
      assertThat(preview.values())
          .containsEntry("note", "service")
          .containsEntry("status", "SERVICE");
      assertThat(f.base.count(f.base.trade)).isZero();
      assertThat(f.ownedService.findMappedResult(command)).isNull();
      var result = f.ownedService.executeMapped(command);
      assertThat(f.ownedService.findMappedResult(command)).isEqualTo(result);
      assertThat(f.ownedService.executeMapped(command)).isEqualTo(result);
      assertThat(f.base.value(f.base.trade, result.recordId(), "note")).isEqualTo("service");
      assertThat(proof(f, result.recordId()).derivedOrigins()).hasSize(1);
      assertThatThrownBy(() -> f.ownedService.findResult(command.command()))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              error ->
                  assertThat(error.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT));
      var targetId = command.confirmations().getFirst().observation().targetRecordId();
      f.base.save(
          f.base.customer,
          f.base.update(f.base.customer, targetId, 0, Map.of("status", "changed")));
      assertThat(f.ownedService.snapshotMapped(command).parent().values())
          .containsEntry("note", "service")
          .containsEntry("status", "SERVICE");
      assertThat(f.ownedService.observeMapping(query).observation().targetVersion()).isEqualTo(1);
      f.authorize(false, "SELF", Map.of());
      assertThatThrownBy(() -> f.ownedService.observeMapping(query))
          .isInstanceOf(DnaRuntimeException.class);
      var cleared =
          f.ownedService.observeMapping(
              new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery(
                  "1",
                  f.base.trade.physicalModelId(),
                  f.schema,
                  1,
                  "trade-customer",
                  null,
                  List.of(),
                  30000));
      assertThat(cleared.observation().targetRecordId()).isNull();
      assertThat(cleared.snapshots())
          .containsEntry(
              "note", org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue.nullValue());
      assertThat(f.ownedService.findMappedResult(command)).isEqualTo(result);
      assertThat(f.ownedService.executeMapped(command)).isEqualTo(result);
      assertThat(f.ownedService.snapshotMapped(command).parent().values())
          .doesNotContainKeys("note", "status");
      assertThatThrownBy(() -> f.ownedService.previewMapped(request))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void relatedPreviewChecksTheAnchorAndMembershipAndUnlinkUsesTheSameMappedPreparation(
      boolean postgres) throws Exception {
    try (var f = fixture(postgres)) {
      var target = f.customer("related");
      var other = f.customer("other");
      var input = input(f, target).command();
      var scope =
          new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope(
              "1",
              "trade-customer",
              f.base.customer.physicalModelId(),
              target,
              org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.Mode.MAINTAIN,
              null);
      var command =
          new MappedOwnedRecordCommand("1", input.command(), scope, input.confirmations());
      assertThat(f.ownedService.previewMapped(command).values()).containsEntry("note", "related");
      assertThat(f.base.count(f.base.trade)).isZero();
      var wrongScope =
          new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope(
              "1",
              "trade-customer",
              f.base.customer.physicalModelId(),
              other,
              org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.Mode.MAINTAIN,
              null);
      var wrong =
          new MappedOwnedRecordCommand("1", input.command(), wrongScope, input.confirmations());
      assertThatThrownBy(() -> f.ownedService.previewMapped(wrong))
          .isInstanceOf(DnaRuntimeException.class);
      var saved = f.ownedService.executeMapped(command);
      var clear =
          f.ownedService.observeMapping(
              new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery(
                  "1",
                  f.base.trade.physicalModelId(),
                  f.schema,
                  1,
                  "trade-customer",
                  null,
                  List.of(),
                  30000));
      var update =
          new OwnedRecordCommand(
              f.command(
                  java.util.UUID.randomUUID().toString(),
                  List.of(
                      new org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation(
                          "parent",
                          saved.recordId(),
                          saved.version(),
                          java.util.Collections.singletonMap("customer_id", null)))),
              List.of());
      var unlink =
          new MappedOwnedRecordCommand(
              "1",
              update,
              new org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope(
                  "1",
                  "trade-customer",
                  f.base.customer.physicalModelId(),
                  target,
                  org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.Mode.UNLINK,
                  null),
              List.of(
                  new org.simplepoint.plugin.dna.runtime.api.record
                      .RecordReferenceMappingConfirmation(
                      "trade-customer", null, null, null, clear.observation(), List.of())));
      var preview = f.ownedService.previewMapped(unlink);
      // The calculator's published UPPER(NULL) contract returns an empty string.
      assertThat(preview.values()).containsEntry("note", null).containsEntry("status", "");
      assertThat(f.base.value(f.base.trade, saved.recordId(), "customer_id")).isEqualTo(target);
      f.authorize(false, "ALL", Map.of());
      assertThatThrownBy(() -> f.ownedService.previewMapped(unlink))
          .isInstanceOf(DnaRuntimeException.class);
      f.authorize(true, "ALL", Map.of());
      f.ownedService.executeMapped(unlink);
      assertThat(f.base.value(f.base.trade, saved.recordId(), "customer_id")).isNull();
      assertThat(f.base.value(f.base.trade, saved.recordId(), "note")).isNull();
      assertThat(f.base.value(f.base.trade, saved.recordId(), "status"))
          .isEqualTo(preview.values().get("status"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void discoversCurrentProofWithoutGrantingItAndOmitsHiddenDestinations(boolean postgres)
      throws Exception {
    try (var f = fixture(postgres)) {
      var input = input(f, f.customer("original"));
      var saved = f.ownedService.executeMapped(input.command());
      var lookup =
          new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery(
              new RecordLookupQuery(
                  f.base.trade.physicalModelId(),
                  f.schema,
                  saved.recordId(),
                  List.of("note", "status")),
              List.of());
      var query =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery(
              "1", lookup, null, null, 30000);
      var original = f.ownedService.currentTargets(query);
      assertThat(original).hasSize(1);
      assertThat(original.getFirst().modelId()).isEqualTo(f.base.customer.physicalModelId());
      assertThat(original.getFirst().resourceCode()).isEqualTo("customers");
      f.authorize(false, "SELF", Map.of());
      assertThat(f.ownedService.currentTargets(query)).isEqualTo(original);
      assertThat(f.ownedService.get(lookup).parent().values()).doesNotContainKeys("note", "status");
      var page =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery(
              f.base.trade.physicalModelId(),
              f.schema,
              0,
              50,
              List.of("note", "status"),
              null,
              List.of());
      assertThat(
              f.ownedService.currentTargets(
                  new org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery(
                      "1", null, page, null, 30000)))
          .isEqualTo(original);
      f.authorize(
          "user",
          true,
          "SELF",
          Map.of(),
          Map.of(
              org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.key(
                          f.base.trade.physicalModelId())
                      + "#note",
                  "HIDDEN",
              org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.key(
                          f.base.trade.physicalModelId())
                      + "#status",
                  "HIDDEN"));
      assertThatThrownBy(() -> f.ownedService.currentTargets(query))
          .isInstanceOf(DnaRuntimeException.class);
      var visibleQuery =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordCurrentTargetsQuery(
              "1",
              new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery(
                  new RecordLookupQuery(
                      f.base.trade.physicalModelId(), f.schema, saved.recordId(), List.of()),
                  List.of()),
              null,
              null,
              30000);
      assertThat(f.ownedService.currentTargets(visibleQuery)).isEmpty();
      f.authorize(true, "SELF", Map.of());
      assertThat(f.ownedService.get(lookup).parent().values())
          .containsEntry("note", "original")
          .containsEntry("status", "ORIGINAL");
    }
  }

  private static RecordReferenceQualificationDatabaseTest.Fixture fixture(boolean postgres)
      throws Exception {
    return fixture(postgres, new DefaultCalciteQueryEngine());
  }

  private static RecordReferenceQualificationDatabaseTest.Fixture fixture(
      boolean postgres, org.simplepoint.data.calcite.core.query.CalciteQueryEngine engine)
      throws Exception {
    return new RecordReferenceQualificationDatabaseTest.Fixture(
        postgres,
        engine,
        null,
        false,
        definition -> {
          var old = definition.referencesOrEmpty().getFirst();
          var rule =
              new RecordReferenceRule(
                  "1",
                  old.relationId(),
                  old.source(),
                  old.targetModelId(),
                  old.targetAppId(),
                  old.targetFormId(),
                  old.targetResourceCode(),
                  null,
                  null,
                  null,
                  List.of(
                      new RecordReferenceMapping(
                          "copy",
                          "status",
                          "note",
                          RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
                          RecordReferenceConversion.IDENTITY,
                          RecordReferenceMapping.NullPolicy.CLEAR)));
          return new RecordCalculationDefinition(
              "1",
              Map.of("status", "UPPER([note])"),
              Map.of(),
              null,
              null,
              null,
              null,
              List.of(rule));
        });
  }

  private static Input input(RecordReferenceQualificationDatabaseTest.Fixture f, String targetId) {
    var admission = new RecordWriteAdmission("tenant-a", 1);
    var plan = f.base.backend.referencePlan(f.base.trade, List.of(), admission, 30000);
    var codec = new PhysicalSchemaCodec(new ObjectMapper());
    var resolver = mock(RuntimeRecordModelResolver.class);
    var access = f.base.access(f.base.trade);
    when(resolver.resolve(
            access.actor(), f.base.trade.physicalModelId(), plan.policy().policy().parentSchema()))
        .thenReturn(f.base.trade);
    var calculations = new RecordCalculationWriteSupport(resolver, codec, f.base.compiler);
    var binding = calculations.bind(access.actor(), f.base.trade, plan.policy());
    var scope = new RecordCalculationWriteSupport.Scope(f.base.trade, access);
    var target = plan.bindings().get("trade-customer").target();
    var query =
        f.base.compiler.lookup(
            target.model(),
            target.access(),
            new RecordLookupQuery(
                target.model().physicalModelId(),
                new RecordSchemaReference(
                    1, codec.encode(target.model().activeSchema()).checksum()),
                targetId,
                List.of("status")));
    var captured =
        f.base.backend.read(
            target.model(),
            List.of(),
            target.access(),
            30000,
            session ->
                RecordQueryServiceImpl.views(
                        session.query().apply(query), query, target.model(), target.access())
                    .getFirst());
    var plain =
        new OwnedRecordCommand(
            f.command(
                UUID.randomUUID().toString(),
                List.of(new RecordCreateMutation("create", Map.of("customer_id", targetId)))),
            List.of());
    var command =
        new MappedOwnedRecordCommand(
            "1",
            plain,
            null,
            List.of(
                new RecordReferenceMappingConfirmation(
                    "trade-customer",
                    null,
                    null,
                    null,
                    plan.mapping("trade-customer").observe(captured),
                    List.of())));
    var operation =
        RecordOperationIdentity.of(
            access.actor(),
            plain.parent(),
            new RecordRequestFingerprint(new ObjectMapper()).of(command));
    var loaded =
        RecordReferenceMappingWrite.load(
            plan,
            command,
            Map.of(f.base.trade.physicalModelId(), scope),
            (selected, ids, fields) ->
                Map.of(
                    targetId,
                    new RecordReferenceMappingTargets.Captured(
                        captured,
                        Map.of(
                            "status",
                            List.of(
                                new RecordReferenceReadRequirement(
                                    target.model().physicalModelId(),
                                    "app",
                                    "customers",
                                    "customers",
                                    targetId,
                                    null,
                                    null,
                                    List.of("status")))))));
    return new Input(
        command, operation, UUID.randomUUID().toString(), binding, scope, calculations, loaded);
  }

  private static RecordBatchExecutionResult execute(
      RecordReferenceQualificationDatabaseTest.Fixture f, Input input) {
    return f.base.backend.executePreparedOwned(
        f.base.trade,
        List.of(),
        new RecordWriteAdmission("tenant-a", 1),
        30000,
        input.operation(),
        (reader, at, policy) -> {
          input.binding().verify(policy);
          var prepared =
              input
                  .calculations()
                  .prepareMapped(
                      input.binding(),
                      input.scope(),
                      Map.of(),
                      input.command().command(),
                      input.rowId(),
                      input.operation(),
                      reader,
                      at,
                      input.loaded());
          return new RecordWritePreparation(
              prepared.statements(), null, 1, prepared.references(), null, prepared.copies());
        });
  }

  private static RecordReferenceCopyStore.Stored proof(
      RecordReferenceQualificationDatabaseTest.Fixture f, String row) {
    var store = new RecordReferenceCopyStore();
    var key = new RecordReferenceCopyStore.Key("tenant-a", f.base.trade.physicalModelId(), row);
    return f.base.backend.read(
        f.base.trade,
        List.of(),
        f.base.access(f.base.trade),
        30000,
        session -> store.read(key, session.query().apply(store.lookup(key))));
  }

  private record Input(
      MappedOwnedRecordCommand command,
      RecordOperationIdentity operation,
      String rowId,
      RecordCalculationWriteSupport.Binding binding,
      RecordCalculationWriteSupport.Scope scope,
      RecordCalculationWriteSupport calculations,
      RecordReferenceMappingWrite loaded) {}
}
