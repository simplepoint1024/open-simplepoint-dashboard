package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationCheckpoint.Phase.APPLYING;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationCheckpoint.Phase.CANCELLED;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationCheckpoint.Phase.COMMITTED;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationCheckpoint.Phase.RESTORING;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;

class RecordPublicationCheckpointTest {
  @Test
  void fullCountersStillRequireExplicitTerminalCommit() {
    var start = RecordPublicationCheckpoint.start(2);
    var staged = start.batch(1, "a").batch(1, "b");
    assertThat(staged.progress().completedRecords()).isEqualTo(2);
    var applying = staged.transition(APPLYING);
    assertThat(applying.progress().completedRecords()).isZero();
    var applied = applying.batch(2, "b");
    assertThat(applied.progress().completedRecords()).isEqualTo(2);
    assertThat(applied.progress()).isNotNull();
    var done = applied.transition(COMMITTED);
    assertThat(done.progress()).isNull();
    assertThat(done.sequence()).isEqualTo(6);
    applied.requireSuccessor(done);
    assertThatThrownBy(() -> done.transition(RESTORING)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> done.failed(DnaRuntimeErrorCode.PERSISTENCE_FAILED)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cancellationRestoresOnlyAppliedAggregatesAndCannotReleaseEarly() {
    var staging = RecordPublicationCheckpoint.start(100).batch(3, "third");
    assertThat(staging.transition(RESTORING).transition(CANCELLED).staged()).isEqualTo(3);
    var restore = RecordPublicationCheckpoint.start(100).batch(100, "last").transition(APPLYING)
        .batch(3, "third").transition(RESTORING);
    assertThat(restore.progress().totalRecords()).isEqualTo(3);
    assertThatThrownBy(() -> restore.transition(CANCELLED)).isInstanceOf(IllegalArgumentException.class);
    var ready = restore.batch(3, "third");
    assertThat(ready.progress()).isNotNull();
    assertThat(ready.transition(CANCELLED).progress()).isNull();
    assertThatThrownBy(() -> restore.transition(APPLYING)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failuresPreserveCursorsAndRetryResumesWithoutResettingTotals() {
    var staged = RecordPublicationCheckpoint.start(2).batch(1, "first");
    var failed = staged.failed(DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED);
    staged.requireSuccessor(failed);
    assertThat(failed.progress().failureCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_TRANSACTION_FAILED);
    assertThat(failed.stagingCursor()).isEqualTo("first");
    var next = failed.batch(1, "last");
    failed.requireSuccessor(next);
    assertThat(next.failureCode()).isNull();
    assertThat(next.sequence()).isEqualTo(4);
  }

  @Test
  void emptyDataCanFinishButInvalidBatchesAndForgedSuccessorsFail() {
    var empty = RecordPublicationCheckpoint.start(0);
    assertThat(empty.transition(APPLYING).transition(COMMITTED).progress()).isNull();
    var start = RecordPublicationCheckpoint.start(2);
    for (long count : new long[]{0, -1, 3, Long.MAX_VALUE}) {
      assertThatThrownBy(() -> start.batch(count, "id")).isInstanceOf(IllegalArgumentException.class);
    }
    for (String cursor : new String[]{null, "", " id", "x".repeat(65)}) {
      assertThatThrownBy(() -> start.batch(1, cursor)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> start.transition(APPLYING)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> start.requireSuccessor(start.batch(1, "a").batch(1, "b")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> start.requireSuccessor(RecordPublicationCheckpoint.start(3).batch(1, "a")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> start.batch(1, "a").batch(1, "a")).isInstanceOf(IllegalArgumentException.class);
  }
}
