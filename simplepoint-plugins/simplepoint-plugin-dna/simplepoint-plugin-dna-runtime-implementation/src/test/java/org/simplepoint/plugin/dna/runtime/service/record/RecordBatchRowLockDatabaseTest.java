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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;

/** A model can be a reference target even when it has no outgoing reference policy. */
class RecordBatchRowLockDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void plainTargetBatchLocksEverySourceRowBeforeItsFirstCalciteMutation(boolean postgres)
      throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    try (var fixture =
            new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, false, true, engine);
        var workers = Executors.newFixedThreadPool(2)) {
      var first = fixture.create(fixture.customer, Map.of("status", "ACTIVE"));
      var second = fixture.create(fixture.customer, Map.of("status", "ACTIVE"));
      fixture.save(fixture.customer, first, second);
      var ids = List.of(first.recordId(), second.recordId()).stream().sorted().toList();
      var reached = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var held = new AtomicBoolean();
      String table = fixture.customer.target().metadataTarget().tableName();
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
      // Preserve caller mutation order, but acquire both locks before executing the first UPDATE.
      var batch =
          workers.submit(
              () -> {
                fixture.save(
                    fixture.customer,
                    fixture.update(fixture.customer, ids.getLast(), 0, Map.of("status", "CHECKED")),
                    fixture.update(
                        fixture.customer, ids.getFirst(), 0, Map.of("status", "CHECKED")));
                return true;
              });
      try {
        boolean paused = reached.await(20, TimeUnit.SECONDS);
        if (!paused && batch.isDone()) batch.get();
        assertThat(paused).isTrue();
        var contender =
            workers.submit(
                () -> {
                  try (var connection = fixture.dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var query =
                        connection.prepareStatement(
                            "SELECT id FROM " + table + " WHERE tenant_id=? AND id=? FOR UPDATE")) {
                      query.setQueryTimeout(20);
                      query.setString(1, "tenant-a");
                      query.setString(2, ids.getFirst());
                      try (var rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                      }
                    } finally {
                      connection.rollback();
                    }
                  }
                  return true;
                });
        fixture.awaitRowLock(fixture.customer);
        assertThat(contender.isDone()).isFalse();
        release.countDown();
        assertThat(batch.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(contender.get(30, TimeUnit.SECONDS)).isTrue();
        for (String id : ids)
          assertThat(((Number) fixture.value(fixture.customer, id, "record_version")).longValue())
              .isEqualTo(1);
      } finally {
        release.countDown();
      }
    }
  }
}
