package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;

class RecordPublicationSessionTest {
  @Test
  void businessWritesRequireTheExactLockedPatchWhileInfrastructureRemainsIndependent() {
    var writes = new AtomicInteger();
    var locks = new AtomicInteger();
    var session = session(writes, plan -> locks.incrementAndGet());
    var patch = statement("business", true);
    session.write(statement("checkpoint", false));
    assertThatThrownBy(() -> session.write(patch)).isInstanceOf(DnaRuntimeException.class);
    assertThat(writes).hasValue(1);
    session.lockRows(new RecordPublicationLockPlan(List.of(patch), List.of(), () -> {}));
    session.write(patch);
    assertThatThrownBy(() -> session.write(statement("changed-patch-same-row", true)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> session.lockRows(new RecordPublicationLockPlan(List.of(), List.of(), () -> {})))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(writes).hasValue(2);
    assertThat(locks).hasValue(1);
  }

  @Test
  void failedLockAcquisitionCannotBeRetriedWithAnotherPlanWithinTheSameTransaction() {
    var writes = new AtomicInteger();
    var attempts = new AtomicInteger();
    var session =
        session(
            writes,
            plan -> {
              attempts.incrementAndGet();
              throw new IllegalStateException("Partial lock acquisition failed");
            });
    var patch = statement("business", true);
    var plan = new RecordPublicationLockPlan(List.of(patch), List.of(), () -> {});
    assertThatThrownBy(() -> session.lockRows(plan)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> session.lockRows(plan)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> session.write(patch)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> session.write(statement("checkpoint-after-failure", false)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(attempts).hasValue(1);
    assertThat(writes).hasValue(0);
  }

  private static RecordPublicationSession session(
      AtomicInteger writes, java.util.function.Consumer<RecordPublicationLockPlan> locks) {
    return new RecordPublicationSession(
        null,
        null,
        null,
        new RecordExecutionDeadline(10_000),
        query -> null,
        query -> null,
        statement -> {
          writes.incrementAndGet();
          return 1;
        },
        locks,
        retirement -> { throw new AssertionError("Unexpected edge retirement"); });
  }

  private static RecordSqlStatement statement(String sql, boolean business) {
    return new RecordSqlStatement(
        sql,
        List.of(),
        RecordMutationType.UPDATE,
        "record",
        1,
        Instant.EPOCH,
        business ? new RecordSqlStatement.Subject("model", "tenant") : null);
  }
}
