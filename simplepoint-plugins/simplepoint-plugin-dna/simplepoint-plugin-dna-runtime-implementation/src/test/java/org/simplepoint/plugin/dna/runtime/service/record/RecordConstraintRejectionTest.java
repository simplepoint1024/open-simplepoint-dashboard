package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Negative receipts preserve rollback and cannot turn into successful writes after a later retry. */
class RecordConstraintRejectionTest {
  @Test
  void diagnosesOnlyBoundedJdbcIntegrityCodesWithoutMatchingMessages() {
    assertThat(RecordConstraintFailures.integrityViolation(new IllegalStateException("duplicate key 23505"))).isFalse();
    assertThat(RecordConstraintFailures.integrityViolation(new SQLException("timeout", "08006"))).isFalse();
    assertThat(RecordConstraintFailures.integrityViolation(new IllegalStateException(new SQLException("private value", "23505")))).isTrue();
    var batch = new SQLException("batch");
    batch.setNextException(new SQLException("constraint", "23000"));
    assertThat(RecordConstraintFailures.integrityViolation(batch)).isTrue();
    var cycle = new IllegalStateException();
    cycle.initCause(new IllegalStateException(cycle));
    assertThat(RecordConstraintFailures.integrityViolation(cycle)).isFalse();
  }

  @Test
  void rejectionRollsBackAllRowsAndFencesDelayedRetriesAfterConflictDisappears() throws Exception {
    var fixture = RuntimeCalciteRecordBackendTest.fixture();
    try (var connection = fixture.dataSource().getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE simpoint_dna_record_operations_v1 (id VARCHAR(36) PRIMARY KEY, "
          + "tenant_id VARCHAR(64), record_version BIGINT, created_by VARCHAR(64), created_at TIMESTAMP, "
          + "updated_by VARCHAR(64), updated_at TIMESTAMP, model_id VARCHAR(64), operation_key VARCHAR(128), "
          + "request_fingerprint VARCHAR(64), result_json VARCHAR(65535))");
    }
    var backend = fixture.backend();
    var admission = new RecordWriteAdmission("tenant-a", 0);
    var existing = RuntimeCalciteRecordBackendTest.insert("duplicate", "tenant-a");
    backend.execute(fixture.model(), admission, List.of(existing), 10_000);
    var operation = new RecordOperationIdentity(UUID.randomUUID().toString(), "tenant-a", "actor-a", "model-1", "failed", "a".repeat(64));
    var mutations = List.of(RuntimeCalciteRecordBackendTest.insert("must-rollback", "tenant-a"), existing);
    assertRejected(() -> backend.execute(fixture.model(), admission, mutations, 10_000, operation));
    var query = new RecordSelectPlan("SELECT \"id\" FROM \"runtime\".\"records\" ORDER BY \"id\"", List.of(), List.of(), 10);
    assertThat(backend.query(fixture.model(), query, 10_000).rows()).containsExactly(List.of("duplicate"));
    try (var connection = fixture.dataSource().getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM records WHERE id='duplicate'");
    }
    assertRejected(() -> backend.execute(fixture.model(), admission, mutations, 10_000, operation));
    assertRejected(() -> backend.findResult(fixture.model(), operation, 10_000));
    assertThat(backend.query(fixture.model(), query, 10_000).rows()).isEmpty();
    var changed = new RecordOperationIdentity(operation.id(), "tenant-a", "actor-a", "model-1", "failed", "b".repeat(64));
    assertThatThrownBy(() -> backend.execute(fixture.model(), admission, mutations, 10_000, changed))
        .isInstanceOfSatisfying(DnaRuntimeException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT));
    var fresh = new RecordOperationIdentity(UUID.randomUUID().toString(), "tenant-a", "actor-a", "model-1", "corrected", "a".repeat(64));
    assertThat(backend.execute(fixture.model(), admission, mutations, 10_000, fresh).affectedRows()).containsExactly(1, 1);
  }

  private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOfSatisfying(DnaRuntimeException.class, failure -> {
      assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_OPERATION_REJECTED);
      assertThat(failure.getMessage()).doesNotContain("duplicate", "must-rollback", "private value");
    });
  }
}
