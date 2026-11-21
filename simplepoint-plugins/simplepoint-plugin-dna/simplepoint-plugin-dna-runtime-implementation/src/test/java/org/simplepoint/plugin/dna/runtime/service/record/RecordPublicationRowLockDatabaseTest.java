package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/** Real stage/apply/restore checkpoints and physical references, including rollback and retry. */
class RecordPublicationRowLockDatabaseTest {
  private static final String CANCEL = "d".repeat(64);

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void applyingAndRestoringPrelockLaterSourcesAndTargetsWithAtomicCheckpoints(boolean postgres)
      throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, false, true, engine)) {
      var customer = f.create(f.customer, Map.of("status", "ACTIVE"));
      var other = f.create(f.customer, Map.of("status", "ACTIVE"));
      f.save(f.customer, customer, other);
      var first = f.create(f.trade, Map.of("customer_id", customer.recordId(), "note", "before"));
      var second = f.create(f.trade, Map.of("customer_id", other.recordId(), "note", "before"));
      f.save(f.trade, first, second);
      var later = List.of(first.recordId(), second.recordId()).stream().sorted().toList().getLast();
      final String laterTarget = (String) f.value(f.trade, later, "customer_id");
      var codec = new PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules());
      var intent = intent(f, codec);
      var models =
          Map.of(
              f.trade.physicalModelId(), new RecordPublicationPatchCompiler.Resolved(f.trade, 0));
      var executor = new RecordPublicationBatchExecutor(codec, f.compiler);
      var entry = session(f, s -> executor.start(intent, models, s, CANCEL));
      while (entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.STAGING) {
        long sequence = entry.checkpoint().sequence();
        entry = session(f, s -> executor.step(intent, models, Map.of(), sequence, 10, CANCEL, s));
      }
      long applySequence = entry.checkpoint().sequence();
      String table = f.trade.target().metadataTarget().tableName();
      var failOnce = new AtomicBoolean();
      doAnswer(
              call -> {
                var request = (CalciteUpdateRequest) call.getArgument(0);
                Object result = call.callRealMethod();
                if (request.sql().startsWith("UPDATE")
                    && request.sql().contains(table)
                    && failOnce.compareAndSet(false, true)) {
                  throw new IllegalStateException(
                      "Injected failure after the first applied reference-bearing row");
                }
                return result;
              })
          .when(engine)
          .executeUpdate(any(), any());
      assertThatThrownBy(
              () ->
                  session(
                      f,
                      s -> executor.step(intent, models, Map.of(), applySequence, 10, CANCEL, s)))
          .isInstanceOf(DnaRuntimeException.class);
      assertThat(failOnce).isTrue();
      assertThat(f.value(f.trade, first.recordId(), "note")).isEqualTo("before");
      assertThat(f.value(f.trade, second.recordId(), "note")).isEqualTo("before");
      assertThat(session(f, s -> executor.start(intent, models, s, CANCEL)).checkpoint().applied())
          .isZero();

      entry =
          heldBatch(
              f,
              engine,
              later,
              laterTarget,
              () ->
                  session(
                      f,
                      s -> executor.step(intent, models, Map.of(), applySequence, 10, CANCEL, s)));
      assertThat(entry.checkpoint().applied()).isEqualTo(2);
      assertThat(f.value(f.trade, first.recordId(), "note")).isEqualTo("published");
      assertThat(f.value(f.trade, second.recordId(), "note")).isEqualTo("published");
      var replay =
          session(f, s -> executor.step(intent, models, Map.of(), applySequence, 10, CANCEL, s));
      assertThat(replay).isEqualTo(entry);
      entry = session(f, s -> executor.requestCancellation(intent, CANCEL, s));
      long restoreSequence = entry.checkpoint().sequence();
      entry =
          heldBatch(
              f,
              engine,
              later,
              laterTarget,
              () ->
                  session(
                      f,
                      s ->
                          executor.step(intent, models, Map.of(), restoreSequence, 10, CANCEL, s)));
      assertThat(entry.checkpoint().restored()).isEqualTo(2);
      for (String id : List.of(first.recordId(), second.recordId())) {
        assertThat(f.value(f.trade, id, "note")).isEqualTo("before");
        assertThat(((Number) f.value(f.trade, id, "record_version")).longValue()).isZero();
      }
      assertThat(f.value(f.trade, later, "customer_id")).isEqualTo(laterTarget);
      long finalSequence = entry.checkpoint().sequence();
      entry =
          session(f, s -> executor.step(intent, models, Map.of(), finalSequence, 10, CANCEL, s));
      assertThat(entry.checkpoint().phase()).isEqualTo(RecordPublicationCheckpoint.Phase.CANCELLED);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void contentionRetriesApplyAndRestoreWithOriginalPatchesAndAtomicCheckpoint(boolean postgres)
      throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    try (var f = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, false, true, engine)) {
      var customer = f.create(f.customer, Map.of("status", "ACTIVE"));
      f.save(f.customer, customer);
      var first = f.create(f.trade, Map.of("customer_id", customer.recordId(), "note", "before"));
      var second = f.create(f.trade, Map.of("customer_id", customer.recordId(), "note", "before"));
      f.save(f.trade, first, second);
      var codec = new PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules());
      var intent = intent(f, codec);
      var models =
          Map.of(
              f.trade.physicalModelId(), new RecordPublicationPatchCompiler.Resolved(f.trade, 0));
      var executor = new RecordPublicationBatchExecutor(codec, f.compiler);
      var entry = session(f, s -> executor.start(intent, models, s, CANCEL));
      while (entry.checkpoint().phase() == RecordPublicationCheckpoint.Phase.STAGING) {
        long sequence = entry.checkpoint().sequence();
        entry = session(f, s -> executor.step(intent, models, Map.of(), sequence, 10, CANCEL, s));
      }
      String table = f.trade.target().metadataTarget().tableName();
      var failNext = new AtomicBoolean(true);
      var writes = new ArrayList<CalciteUpdateRequest>();
      doAnswer(
              call -> {
                var request = (CalciteUpdateRequest) call.getArgument(0);
                Object result = call.callRealMethod();
                if (request.sql().startsWith("UPDATE") && request.sql().contains(table)) {
                  writes.add(request);
                  if (failNext.compareAndSet(true, false)) {
                    throw new IllegalStateException(
                        "Injected contention after first patch",
                        new SQLException(
                            "Opaque", postgres ? "40P01" : "40001", postgres ? 0 : 1213));
                  }
                }
                return result;
              })
          .when(engine)
          .executeUpdate(any(), any());
      long apply = entry.checkpoint().sequence();
      entry = session(f, s -> executor.step(intent, models, Map.of(), apply, 10, CANCEL, s));
      assertThat(entry.checkpoint().applied()).isEqualTo(2);
      assertThat(entry.checkpoint().sequence()).isEqualTo(apply + 1);
      assertThat(writes).hasSize(3);
      assertThat(writes.getFirst().parameters()).isEqualTo(writes.get(1).parameters());
      for (String id : List.of(first.recordId(), second.recordId())) {
        assertThat(f.value(f.trade, id, "note")).isEqualTo("published");
        assertThat(((Number) f.value(f.trade, id, "record_version")).longValue()).isEqualTo(1);
      }
      entry = session(f, s -> executor.requestCancellation(intent, CANCEL, s));
      long restore = entry.checkpoint().sequence();
      failNext.set(true);
      entry = session(f, s -> executor.step(intent, models, Map.of(), restore, 10, CANCEL, s));
      assertThat(entry.checkpoint().restored()).isEqualTo(2);
      assertThat(entry.checkpoint().sequence()).isEqualTo(restore + 1);
      assertThat(writes).hasSize(6);
      assertThat(writes.get(3).parameters()).isEqualTo(writes.get(4).parameters());
      for (String id : List.of(first.recordId(), second.recordId())) {
        assertThat(f.value(f.trade, id, "note")).isEqualTo("before");
        assertThat(((Number) f.value(f.trade, id, "record_version")).longValue()).isZero();
      }
      RecordPublicationStore.Entry replay =
          session(f, s -> executor.step(intent, models, Map.of(), restore, 10, CANCEL, s));
      assertThat(replay).isEqualTo(entry);
      assertThat(writes).hasSize(6);
    }
  }

  private static RecordPublicationStore.Entry heldBatch(
      RecordReferenceIntegrityDatabaseTest.Fixture f,
      DefaultCalciteQueryEngine engine,
      String later,
      String target,
      Callable<RecordPublicationStore.Entry> batch)
      throws Exception {
    var reached = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var held = new AtomicBoolean();
    String table = f.trade.target().metadataTarget().tableName();
    doAnswer(
            call -> {
              var request = (CalciteUpdateRequest) call.getArgument(0);
              if (request.sql().startsWith("UPDATE")
                  && request.sql().contains(table)
                  && held.compareAndSet(false, true)) {
                reached.countDown();
                assertThat(release.await(25, TimeUnit.SECONDS)).isTrue();
              }
              return call.callRealMethod();
            })
        .when(engine)
        .executeUpdate(any(), any());
    try (var workers = Executors.newFixedThreadPool(3)) {
      var write = workers.submit(batch);
      try {
        boolean paused = reached.await(20, TimeUnit.SECONDS);
        if (!paused && write.isDone()) {
          write.get();
        }
        assertThat(paused).isTrue();
        var sourceWaiter = workers.submit(() -> lock(f, f.trade, later));
        final var targetWaiter = workers.submit(() -> lock(f, f.customer, target));
        f.awaitRowLock(f.trade);
        f.awaitRowLock(f.customer);
        assertThat(sourceWaiter.isDone()).isFalse();
        assertThat(targetWaiter.isDone()).isFalse();
        release.countDown();
        var result = write.get(30, TimeUnit.SECONDS);
        assertThat(sourceWaiter.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(targetWaiter.get(30, TimeUnit.SECONDS)).isTrue();
        return result;
      } finally {
        release.countDown();
      }
    }
  }

  private static boolean lock(
      RecordReferenceIntegrityDatabaseTest.Fixture f, RuntimeRecordModel model, String id)
      throws Exception {
    try (var connection = f.dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var query =
          connection.prepareStatement(
              "SELECT id FROM "
                  + model.target().metadataTarget().tableName()
                  + " WHERE tenant_id=? AND id=? FOR UPDATE")) {
        query.setQueryTimeout(20);
        query.setString(1, "tenant-a");
        query.setString(2, id);
        try (var rows = query.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      } finally {
        connection.rollback();
      }
    }
    return true;
  }

  private static <T> T session(
      RecordReferenceIntegrityDatabaseTest.Fixture f,
      java.util.function.Function<RecordPublicationSession, T> work) {
    return f.backend.publication(
        f.trade,
        List.of(),
        new RecordWriteFenceKey("tenant-a", f.trade.physicalModelId()),
        30_000,
        work);
  }

  private static RecordPublicationIntent intent(
      RecordReferenceIntegrityDatabaseTest.Fixture f, PhysicalSchemaCodec codec) {
    var schema = new RecordSchemaReference(1, codec.encode(f.trade.activeSchema()).checksum());
    var rule =
        new RecordReferenceRule(
            "1",
            "trade-customer",
            new RecordValidationField("parent", "customer"),
            f.customer.physicalModelId(),
            "app",
            "customers",
            "customers",
            null,
            null);
    var definition =
        new RecordCalculationDefinition(
            "1", Map.of("note", "\"published\""), Map.of(), null, null, null, null, List.of(rule));
    var command =
        new RecordWritePolicyPublicationCommand(
            f.trade.physicalModelId(),
            "app",
            "trades",
            schema,
            0,
            1,
            "publish-reference-note",
            "c".repeat(64),
            Map.of(),
            30_000,
            Map.of(),
            definition);
    return new RecordPublicationIntent(
        1,
        new RecordWriteFenceKey("tenant-a", f.trade.physicalModelId()),
        command,
        "c".repeat(64),
        "0".repeat(64),
        "publisher",
        Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
        Map.of(
            f.trade.physicalModelId(),
            new RecordPublicationIntent.Binding(
                schema, schema, f.trade.target().metadataTarget(), 0)),
        null);
  }
}
