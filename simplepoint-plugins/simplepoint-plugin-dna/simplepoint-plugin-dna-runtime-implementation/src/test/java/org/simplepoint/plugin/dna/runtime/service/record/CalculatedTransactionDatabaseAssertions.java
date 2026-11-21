package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Real transaction scenarios using the already provisioned PostgreSQL/MySQL owned-record fixture.
 */
final class CalculatedTransactionDatabaseAssertions {
  private CalculatedTransactionDatabaseAssertions() {}

  static void verify(
      RuntimeActor actor,
      RecordSchemaReference schema,
      RuntimeRecordModel model,
      RuntimeRecordAccessPolicy policy,
      RuntimeRecordModelResolver models,
      RuntimeCalciteRecordBackend backend,
      RecordSqlCompiler compiler,
      OwnedRecordService owned)
      throws Exception {
    var context = mock(RuntimeRequestContext.class);
    when(context.requireActor()).thenReturn(actor);
    var mapper = new ObjectMapper().findAndRegisterModules();
    var audit = mock(RecordAuditSupport.class);
    var observer = mock(RecordExecutionObserver.class);
    var admission = new RecordAdmissionController(new DnaRuntimeExecutionProperties());
    var fingerprints = new RecordRequestFingerprint(mapper);
    var transactions =
        new RecordTransactionServiceImpl(
            context,
            models,
            policy,
            compiler,
            backend,
            audit,
            admission,
            observer,
            fingerprints,
            new RecordCalculationWriteSupport(models, new PhysicalSchemaCodec(mapper), compiler));
    final var queries =
        new RecordQueryServiceImpl(
            context,
            models,
            policy,
            compiler,
            backend,
            audit,
            admission,
            observer,
            RecordCalculationTestSupport.access(models, policy, compiler));
    var detail = new OwnedRecordModel("child-1", schema);
    var seed =
        new OwnedRecordCommand(
            transaction(
                schema, "seq-seed", new RecordCreateMutation("create", Map.of("title", "出差明细"))),
            List.of(
                new OwnedRecordSet(
                    detail,
                    List.of(
                        new OwnedRecordRow(
                            null,
                            null,
                            Map.of("title", "交通", "unit_price", "10.01", "quantity", 3)),
                        new OwnedRecordRow(
                            null,
                            null,
                            Map.of("title", "住宿", "unit_price", "8.25", "quantity", 2))))));
    var created = owned.execute(seed);
    var sequence =
        transaction(
            schema,
            "seq-update",
            new RecordUpdateMutation("rate", created.recordId(), 0, Map.of("exchange_rate", "2")),
            new RecordUpdateMutation("memo", created.recordId(), 1, Map.of("title", "已复核")));
    var result = transactions.execute(sequence);
    assertThat(result.results()).extracting(row -> row.result().version()).containsExactly(1L, 2L);
    assertThat(result.results().getFirst().result().updatedAt())
        .isEqualTo(result.results().getLast().result().updatedAt());
    var current = owned.get(query(schema, detail, created.recordId()));
    assertThat(current.parent().values())
        .containsEntry("amount", "23.27")
        .containsEntry("exchange_rate", "2.0000")
        .containsEntry("computed_at", result.results().getFirst().result().updatedAt().toString());
    assertThat(current.details().get("child-1"))
        .extracting(RecordView::version)
        .containsExactly(2L, 2L);
    assertThat(transactions.execute(sequence).results()).isEqualTo(result.results());
    assertThat(transactions.findResult(sequence).results()).isEqualTo(result.results());

    var failure =
        transaction(
            schema,
            "seq-rollback",
            new RecordUpdateMutation("first", created.recordId(), 2, Map.of("exchange_rate", "1")),
            new RecordUpdateMutation(
                "second", created.recordId(), 3, Map.of("exchange_rate", "0")));
    assertThatThrownBy(() -> transactions.execute(failure)).isInstanceOf(DnaRuntimeException.class);
    assertThat(transactions.findResult(failure)).isNull();
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);
    var aggregateFailure =
        transaction(
            schema,
            "seq-aggregate-validation",
            new RecordUpdateMutation("first", created.recordId(), 2, Map.of("exchange_rate", "1")),
            new RecordUpdateMutation(
                "second", created.recordId(), 3, Map.of("exchange_rate", "0.0001")));
    assertThatThrownBy(() -> transactions.execute(aggregateFailure))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThat(transactions.findResult(aggregateFailure)).isNull();
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);
    var historicalFailure =
        transaction(
            schema,
            "seq-historical-validation",
            new RecordUpdateMutation("first", created.recordId(), 2, Map.of("exchange_rate", "1")),
            new RecordUpdateMutation("second", created.recordId(), 3, Map.of("title", "禁止")));
    assertThatThrownBy(() -> transactions.execute(historicalFailure))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThat(transactions.findResult(historicalFailure)).isNull();
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);
    var transitionFailure =
        transaction(
            schema,
            "seq-lifecycle-validation",
            new RecordUpdateMutation(
                "submit", created.recordId(), 2, Map.of("lifecycle_state", "SUBMITTED")),
            new RecordUpdateMutation(
                "draft", created.recordId(), 3, Map.of("lifecycle_state", "DRAFT")));
    assertThatThrownBy(() -> transactions.execute(transitionFailure))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThat(transactions.findResult(transitionFailure)).isNull();
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);
    var lockedFailure =
        transaction(
            schema,
            "seq-field-write-validation",
            new RecordUpdateMutation(
                "submit", created.recordId(), 2, Map.of("lifecycle_state", "SUBMITTED")),
            new RecordUpdateMutation("locked", created.recordId(), 3, Map.of("title", "不能修改")));
    assertThatThrownBy(() -> transactions.execute(lockedFailure))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThat(transactions.findResult(lockedFailure)).isNull();
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);
    var conflict =
        transaction(
            schema,
            "seq-version-conflict",
            new RecordUpdateMutation("first", created.recordId(), 2, Map.of("exchange_rate", "1")),
            new RecordUpdateMutation("second", created.recordId(), 2, Map.of("title", "错误版本")));
    assertThatThrownBy(() -> transactions.execute(conflict))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));
    assertThat(transactions.findResult(conflict)).isNull();
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);
    var invalidSource =
        transaction(
            schema,
            "seq-source-rule",
            new RecordUpdateMutation("invalid", created.recordId(), 2, Map.of("title", "短")));
    assertThatThrownBy(() -> transactions.execute(invalidSource))
        .isInstanceOf(DnaRuntimeException.class);
    var invalidResult =
        transaction(
            schema,
            "seq-result-rule",
            new RecordUpdateMutation(
                "invalid", created.recordId(), 2, Map.of("title", "长".repeat(21))));
    assertThatThrownBy(() -> transactions.execute(invalidResult))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(transactions.findResult(invalidSource)).isNull();
    assertThat(transactions.findResult(invalidResult)).isNull();
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);
    var forged =
        transaction(
            schema,
            "seq-forged",
            new RecordUpdateMutation("first", created.recordId(), 2, Map.of("exchange_rate", "1")),
            new RecordUpdateMutation("second", created.recordId(), 3, Map.of("amount", "999")));
    assertThatThrownBy(() -> transactions.execute(forged)).isInstanceOf(DnaRuntimeException.class);
    assertThat(owned.get(query(schema, detail, created.recordId()))).isEqualTo(current);

    var batch =
        transaction(
            schema,
            "seq-batch-create",
            new RecordCreateMutation("a", Map.of("title", "批次创建")),
            new RecordCreateMutation("b", Map.of("title", "批次创建")));
    var start = new CountDownLatch(1);
    RecordTransactionResult batchResult;
    try (var threads = Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                return transactions.execute(batch);
              });
      var second =
          threads.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                return transactions.execute(batch);
              });
      start.countDown();
      batchResult = first.get(40, TimeUnit.SECONDS);
      assertThat(second.get(40, TimeUnit.SECONDS).results()).isEqualTo(batchResult.results());
    }
    assertThat(batchResult.results())
        .extracting(row -> row.result().recordId())
        .doesNotHaveDuplicates();
    assertThat(batchResult.results().getFirst().result().updatedAt())
        .isEqualTo(batchResult.results().getLast().result().updatedAt());
    assertThat(
            queries.count(
                new RecordCountQuery(
                    "model-1",
                    schema,
                    new RecordFilter(
                        RecordFilterOperator.EQUALS, "title", List.of("批次创建"), List.of()))))
        .isEqualTo(2);
    var delete =
        transaction(
            schema, "seq-delete", new RecordDeleteMutation("delete", created.recordId(), 2));
    transactions.execute(delete);
    assertThat(transactions.execute(delete).results())
        .isEqualTo(transactions.findResult(delete).results());
    var childAccess =
        policy.owned(
            actor, models.resolve(actor, "child-1", schema), policy.authorize(actor, model));
    var childModel = models.resolve(actor, "child-1", schema);
    var count =
        compiler.count(
            childModel,
            new RecordAccessContext(
                childAccess.actor(),
                childAccess.resourceCode(),
                java.util.Set.of("parent_record_id"),
                childAccess.writableFields(),
                java.util.Set.of(),
                childAccess.rowScope(),
                null),
            new RecordFilter(
                RecordFilterOperator.EQUALS,
                "parent_record_id",
                List.of(created.recordId()),
                List.of()));
    assertThat(
            ((Number) backend.query(childModel, count, 30_000).rows().getFirst().getFirst())
                .intValue())
        .isZero();
    for (var entry : batchResult.results()) {
      transactions.execute(
          transaction(
              schema,
              "cleanup-" + entry.reference(),
              new RecordDeleteMutation("delete", entry.result().recordId(), 0)));
    }

    var receipts = mock(RecordCreateReceiptStore.class);
    String id = UUID.randomUUID().toString();
    Instant at = Instant.parse("2026-10-17T10:01:02.123456Z");
    var claim = new RecordCreateReceiptClaim(id, at, null);
    when(receipts.claim(any(), any(), any())).thenReturn(claim);
    when(receipts.find(any(), any(), any())).thenReturn(claim);
    when(receipts.complete(any(), any(), any(), any())).thenAnswer(call -> call.getArgument(3));
    var commands =
        new RecordCommandServiceImpl(
            new RecordCreateCoordinator(transactions, receipts, fingerprints), transactions);
    var create =
        new RecordCreateCommand("model-1", schema, "single-calculated", Map.of("title", "独立创建"), 1);
    var commandResult = commands.create(create);
    assertThat(commandResult.recordId()).isEqualTo(id);
    assertThat(commandResult.updatedAt()).isEqualTo(at);
    assertThat(owned.get(query(schema, detail, id)).parent().values())
        .containsEntry("amount", "0.00")
        .containsEntry("computed_at", at.toString());
    assertThat(commands.create(create)).isEqualTo(commandResult);
    assertThat(commands.findCreateResult(create)).isEqualTo(commandResult);
    var update =
        new RecordUpdateCommand(
            "model-1", schema, id, 0, Map.of("exchange_rate", "3"), "single-update", 1);
    var updated = commands.update(update);
    assertThat(commands.update(update)).isEqualTo(updated);
    assertThat(commands.findUpdateResult(update)).isEqualTo(updated);
    commands.delete(new RecordDeleteCommand("model-1", schema, id, 1, "single-delete", 1));
    assertThat(commands.findCreateResult(create)).isEqualTo(commandResult);

    // Exceed the whole sequence's physical budget after one valid write, proving rollback.
    var access = policy.authorize(actor, model);
    var planned =
        compiler.mutation(
            model, access, new RecordCreateMutation("budget", Map.of("title", "预算回滚")), at);
    var reachedSecondStep = new java.util.concurrent.atomic.AtomicBoolean();
    assertThatThrownBy(
            () ->
                backend.executePreparedSequence(
                    model,
                    List.of(),
                    new RecordWriteAdmission(actor.tenantId(), 1),
                    30_000,
                    null,
                    List.of(
                        (reader, time, installed) ->
                            new RecordWritePreparation(List.of(planned), null),
                        (reader, time, installed) -> {
                          assertThat(
                                  reader
                                      .apply(compiler.creation(model, access, planned.recordId()))
                                      .rows())
                              .hasSize(1);
                          reachedSecondStep.set(true);
                          return new RecordWritePreparation(
                              java.util.Collections.nCopies(401, planned), null);
                        }),
                    (reader, installed) ->
                        new RecordSequenceLockPlan(
                            List.of(
                                new RecordSequenceLockPlan.Source(
                                    model.physicalModelId(), planned.recordId())),
                            List.of(),
                            () -> {})))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_COMMAND_INVALID));
    assertThat(reachedSecondStep).isTrue();
    assertThat(
            backend
                .query(model, compiler.creation(model, access, planned.recordId()), 30_000)
                .rows())
        .isEmpty();
  }

  private static RecordTransactionCommand transaction(
      RecordSchemaReference schema, String key, RecordMutation... mutations) {
    return new RecordTransactionCommand("model-1", schema, List.of(mutations), 30_000, key, 1);
  }

  private static OwnedRecordQuery query(
      RecordSchemaReference schema, OwnedRecordModel detail, String id) {
    return new OwnedRecordQuery(
        new RecordLookupQuery(
            "model-1", schema, id, List.of("title", "amount", "exchange_rate", "computed_at")),
        List.of(detail));
  }
}
