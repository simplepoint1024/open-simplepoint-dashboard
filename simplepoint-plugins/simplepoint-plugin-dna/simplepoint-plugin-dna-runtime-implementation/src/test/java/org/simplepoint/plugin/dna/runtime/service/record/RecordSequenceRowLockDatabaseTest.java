package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;

/**
 * Actual installed reference policy and public transaction service, not preconstructed SQL groups.
 */
class RecordSequenceRowLockDatabaseTest {
  @AfterEach
  void cleanupContext() {
    clearContext();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void laterStepSourceAndTargetAreLockedBeforeFirstWriteWithoutChangingSequentialSemantics(
      boolean postgres) throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    try (var f = new RecordReferenceQualificationDatabaseTest.Fixture(postgres, engine);
        var workers = Executors.newFixedThreadPool(3)) {
      String oldTarget = f.customer("ACTIVE");
      String nextTarget = f.customer("ACTIVE");
      var first =
          f.execute(
              new RecordCreateMutation(
                  "first", Map.of("customer_id", oldTarget, "status", "DRAFT")));
      var second =
          f.execute(
              new RecordCreateMutation(
                  "second", Map.of("customer_id", oldTarget, "status", "DRAFT")));
      var command =
          f.command(
              "ordered-sequence",
              List.of(
                  new RecordUpdateMutation("note", first.recordId(), 0, Map.of("note", "first")),
                  new RecordUpdateMutation(
                      "retarget", second.recordId(), 0, Map.of("customer_id", nextTarget)),
                  new RecordUpdateMutation(
                      "submit", second.recordId(), 1, Map.of("status", "SUBMITTED"))));
      var reached = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var held = new AtomicBoolean();
      String table = f.base.trade.target().metadataTarget().tableName();
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
      var sequence =
          workers.submit(
              () -> {
                f.authorize(true, "SELF", Map.of());
                try {
                  return f.service.execute(command);
                } finally {
                  clearContext();
                }
              });
      try {
        boolean paused = reached.await(20, TimeUnit.SECONDS);
        if (!paused && sequence.isDone()) {
          sequence.get();
        }
        assertThat(paused).isTrue();
        var source = workers.submit(() -> lock(f, f.base.trade, second.recordId()));
        final var target = workers.submit(() -> lock(f, f.base.customer, nextTarget));
        f.base.awaitRowLock(f.base.trade);
        f.base.awaitRowLock(f.base.customer);
        assertThat(source.isDone()).isFalse();
        assertThat(target.isDone()).isFalse();
        release.countDown();
        var result = sequence.get(30, TimeUnit.SECONDS);
        assertThat(source.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(target.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(result.results())
            .extracting(value -> value.result().version())
            .containsExactly(1L, 1L, 2L);
        assertThat(f.base.value(f.base.trade, second.recordId(), "customer_id"))
            .isEqualTo(nextTarget);
        assertThat(f.base.value(f.base.trade, second.recordId(), "status")).isEqualTo("SUBMITTED");
        assertThat(f.service.execute(command).results()).isEqualTo(result.results());
      } finally {
        release.countDown();
      }
    }
  }

  private static boolean lock(
      RecordReferenceQualificationDatabaseTest.Fixture f, RuntimeRecordModel model, String id)
      throws Exception {
    try (var connection = f.base.dataSource.getConnection()) {
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

  private static void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }
}
