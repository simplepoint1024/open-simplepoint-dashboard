package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.transaction;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.update;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** PostgreSQL verifies the cancellation fence and the boundary between unstarted and staged work. */
class RecordUnstartedCancellationDatabaseTest {
  @Test
  void unstartedCancellationIsDurableAndRejectsDelayedPublication() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("POSTGRES", true, db -> {
      var fixture = db.fixture();
      var intent = fixture.intent;
      var actor = new RuntimeActor(intent.key().tenantId(), intent.actorId());
      try (var connection = transaction(db)) {
        db.fence().initialize(connection, intent.key(), actor.actorId(), 30000);
        connection.commit();
      }
      var backend = db.backend();
      var first = backend.cancelUnstartedCalculationPolicy(fixture.parent, actor, intent.command(),
          intent.fingerprint(), "e".repeat(64), new RecordExecutionDeadline(30000)).orElseThrow();
      assertThat(first.cancelled()).isTrue();
      var retry = backend.cancelUnstartedCalculationPolicy(fixture.parent, actor, intent.command(),
          intent.fingerprint(), "e".repeat(64), new RecordExecutionDeadline(30000)).orElseThrow();
      assertThat(retry).isEqualTo(first);
      assertThatThrownBy(() -> backend.publishWritePolicy(fixture.parent, actor, intent.command(),
          intent.fingerprint(), Map.of(), Map.of(), new RecordExecutionDeadline(30000)))
          .isInstanceOf(DnaRuntimeException.class);
    });
  }

  @Test
  void durableBatchCannotBeCancelledThroughTheUnstartedPath() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("POSTGRES", true, db -> {
      var fixture = db.fixture();
      var intent = fixture.intent;
      var actor = new RuntimeActor(intent.key().tenantId(), intent.actorId());
      try (var connection = transaction(db)) {
        db.fence().initialize(connection, intent.key(), actor.actorId(), 30000);
        db.fence().lockPublication(connection, intent.key(), 0, new RecordExecutionDeadline(30000));
        var journal = new RecordPublicationStore();
        assertThat(update(db, connection, journal.insert(
            new RecordPublicationStore.Entry(intent, RecordPublicationCheckpoint.start(0))))).isEqualTo(1);
        connection.commit();
      }
      assertThat(db.backend().cancelUnstartedCalculationPolicy(fixture.parent, actor, intent.command(),
          intent.fingerprint(), "e".repeat(64), new RecordExecutionDeadline(30000))).isEmpty();
      try (var connection = transaction(db)) {
        var unchanged = db.fence().lockPublication(connection, intent.key(), new RecordExecutionDeadline(30000));
        assertThat(unchanged.revision()).isZero();
        connection.commit();
      }
    });
  }
}
