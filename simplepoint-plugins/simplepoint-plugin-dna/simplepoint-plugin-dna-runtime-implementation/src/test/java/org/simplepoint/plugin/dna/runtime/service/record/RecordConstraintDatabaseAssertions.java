package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;

/** Executed on both real dialects with the production Calcite transaction and receipt tables. */
final class RecordConstraintDatabaseAssertions {
  private RecordConstraintDatabaseAssertions() {
  }

  static void verify(DataSource source, RuntimeRecordModel model, RuntimeCalciteRecordBackend backend,
      RecordSqlCompiler compiler, RecordAccessContext access, AtomicInteger failNthCommit) throws Exception {
    var admission = new RecordWriteAdmission("tenant-a", 0);
    var existing = compiler.mutation(model, access,
        new RecordCreateMutation("constraint-existing", Map.of("title", "constraint fixture")), Instant.now());
    var partial = compiler.mutation(model, access,
        new RecordCreateMutation("constraint-partial", Map.of("title", "must roll back")), Instant.now());
    backend.execute(model, admission, List.of(existing), 15_000);
    var rejected = identity("constraint-concurrent");
    var mutations = List.of(partial, existing);
    var gate = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
        gate.await();
        assertCode(() -> backend.execute(model, admission, mutations, 15_000, rejected), DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
        return true;
      })).toList();
      gate.countDown();
      for (var future : futures) {
        assertThat(future.get(45, TimeUnit.SECONDS)).isTrue();
      }
    }
    assertThat(backend.query(model, new RecordSelectPlan("SELECT \"id\" FROM \"runtime\".\"records\" WHERE \"id\" = ?",
        List.of(partial.recordId()), List.of(), 1), 15_000).rows()).isEmpty();
    var uncertain = identity("constraint-commit-loss");
    // The first commit closes the receipt-recovery read, the second persists the rejection.
    failNthCommit.set(2);
    assertCode(() -> backend.execute(model, admission, List.of(existing), 15_000, uncertain), DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED);
    assertCode(() -> backend.findResult(model, uncertain, 15_000), DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
    try (var connection = source.getConnection()) {
      var target = model.target().metadataTarget();
      if (target.schemaName() != null && !target.schemaName().isBlank()) {
        connection.setSchema(target.schemaName());
      } else {
        connection.setCatalog(target.catalogName());
      }
      try (var statement = connection.prepareStatement("DELETE FROM records WHERE id = ?")) {
        statement.setString(1, existing.recordId());
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
    }
    assertCode(() -> backend.execute(model, admission, mutations, 15_000, rejected), DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
    assertCode(() -> backend.execute(model, admission, List.of(existing), 15_000, uncertain), DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
    var changed = new RecordOperationIdentity(rejected.id(), rejected.tenantId(), rejected.actorId(), rejected.modelId(),
        rejected.key(), "b".repeat(64));
    assertCode(() -> backend.execute(model, admission, mutations, 15_000, changed), DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT);
    assertThat(backend.execute(model, admission, mutations, 15_000, identity("constraint-corrected")).affectedRows()).containsExactly(1, 1);
  }

  private static RecordOperationIdentity identity(String key) {
    return new RecordOperationIdentity(UUID.randomUUID().toString(), "tenant-a", "user-a", "model-a", key, "a".repeat(64));
  }

  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, DnaRuntimeErrorCode code) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }
}
