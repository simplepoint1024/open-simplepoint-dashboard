package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.locked;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.transaction;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.update;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordPublicationSchema;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Real target locks, durable admission between batches, and receipt/snapshot bypass regression. */
class RecordMaintenanceDatabaseTest {
  private static final int TIMEOUT = 30_000;
  private static final RecordPublicationStore JOURNAL = new RecordPublicationStore();

  @Test
  void postgresqlFencesReadsWritesReplaysAndLegacyTransitionsUntilTerminalCommit() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("POSTGRES", true, RecordMaintenanceDatabaseTest::verify);
  }

  @Test
  void mysqlFencesReadsWritesReplaysAndLegacyTransitionsUntilTerminalCommit() throws Exception {
    RecordPublicationPatchDatabaseTest.verify("MYSQL", false, RecordMaintenanceDatabaseTest::verify);
  }

  private static void verify(Database db) throws Exception {
    var fixture = db.fixture();
    var intent = fixture.intent;
    var key = intent.key();
    var backend = db.backend();
    var operation = new RecordOperationIdentity(RecordRequestFingerprint.deterministicOperationId(key.tenantId(), key.modelId(), "seed"),
        key.tenantId(), intent.actorId(), key.modelId(), "seed", "d".repeat(64));
    try (var connection = transaction(db)) {
      db.fence().initialize(connection, key, intent.actorId(), TIMEOUT);
      db.fence().initialize(connection, new RecordWriteFenceKey("tenant-b", key.modelId()), intent.actorId(), TIMEOUT);
      connection.commit();
    }
    backend.executeOwned(fixture.parent, List.of(fixture.child), new RecordWriteAdmission(key.tenantId(), 0), fixture.seed(), TIMEOUT, operation);
    var receipt = backend.findResult(fixture.parent, operation, TIMEOUT);
    assertThat(receipt).hasSize(1);
    var access = new RecordAccessContext(new RuntimeActor(key.tenantId(), intent.actorId()), null, Set.of(), Set.of(), Set.of(), null, null);
    var initial = new RecordPublicationStore.Entry(intent, RecordPublicationCheckpoint.start(0));
    // A previously admitted read completes before durable maintenance can be claimed.
    var reading = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var claiming = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var read = executor.submit(() -> backend.read(fixture.parent, List.of(fixture.child), access, TIMEOUT, session -> {
        reading.countDown();
        await(release);
        assertThat(session.receipt(operation)).isEqualTo(receipt);
        return true;
      }));
      try {
        assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
        var claim = executor.submit(() -> {
          try (var connection = transaction(db)) {
            claiming.countDown();
            db.fence().lockPublication(connection, key, 0, new RecordExecutionDeadline(TIMEOUT));
            assertThat(update(db, connection, JOURNAL.insert(initial))).isEqualTo(1);
            connection.commit();
          }
          return true;
        });
        assertThat(claiming.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> claim.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(read.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(claim.get(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
    var applying = advance(db, initial, initial.checkpoint().transition(RecordPublicationCheckpoint.Phase.APPLYING), false);
    // Rolled-back phase changes leave admission closed; a progress count of 100% is not completion.
    blocked(db, access, operation);
    applying = advance(db, initial, applying.checkpoint(), true);
    blocked(db, access, operation);
    var restoring = advance(db, applying, applying.checkpoint().transition(RecordPublicationCheckpoint.Phase.RESTORING), true);
    blocked(db, access, operation);
    var cancelled = restoring.checkpoint().transition(RecordPublicationCheckpoint.Phase.CANCELLED);
    // A falsely terminal journal cannot release admission without consuming the policy version.
    advance(db, restoring, cancelled, true);
    assertCode(() -> backend.findResult(fixture.parent, operation, TIMEOUT), DnaRuntimeErrorCode.PERSISTENCE_FAILED);
    try (var connection = locked(db)) {
      db.fence().cancel(connection, key, 0, 1, intent.fingerprint(), "e".repeat(64), intent.actorId(), new RecordExecutionDeadline(TIMEOUT));
      connection.commit();
    }
    assertThat(backend.findResult(fixture.parent, operation, TIMEOUT)).isEqualTo(receipt);
    assertThat(backend.executeOwned(fixture.parent, List.of(fixture.child), new RecordWriteAdmission(key.tenantId(), 0),
        fixture.seed(), TIMEOUT, operation).replayedResults()).isEqualTo(receipt);
    var readReceipt = backend.read(fixture.parent, List.of(fixture.child), access, TIMEOUT, session -> session.receipt(operation));
    assertThat(readReceipt).isEqualTo(receipt);
    // Missing admission infrastructure must fail closed, including the receipt recovery path.
    try (var connection = db.source().getConnection(); var statement = connection.createStatement()) {
      String table = db.core().qualifyName(db.schema() == null ? db.catalog() : null, db.schema(), RecordPublicationSchema.TABLE, db.context());
      statement.execute("DROP TABLE " + table);
    }
    assertThatThrownBy(() -> backend.findResult(fixture.parent, operation, TIMEOUT)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> backend.executeOwned(fixture.parent, List.of(fixture.child), new RecordWriteAdmission(key.tenantId(), 1),
        fixture.seed(), TIMEOUT, operation)).isInstanceOf(DnaRuntimeException.class);
  }

  private static void blocked(Database db, RecordAccessContext access, RecordOperationIdentity operation) throws Exception {
    var fixture = db.fixture();
    var backend = db.backend();
    var intent = fixture.intent;
    var key = intent.key();
    var query = new RecordSelectPlan("SELECT COUNT(*) FROM \"runtime\".\"patch_parents\" WHERE \"tenant_id\" = ?",
        List.of(key.tenantId()), List.of(), 1);
    assertCode(() -> backend.query(fixture.parent, key.tenantId(), query, TIMEOUT), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertCode(() -> backend.aggregateQuery(fixture.parent, key.tenantId(), query, TIMEOUT), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertCode(() -> backend.calculation(fixture.parent, key.tenantId(), 0, TIMEOUT), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertCode(() -> backend.findResult(fixture.parent, operation, TIMEOUT), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertCode(() -> backend.executeOwned(fixture.parent, List.of(fixture.child), new RecordWriteAdmission(key.tenantId(), 0),
        fixture.seed(), TIMEOUT, operation), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertCode(() -> backend.executeOwned(fixture.parent, List.of(fixture.child), new RecordWriteAdmission(key.tenantId(), 0),
        fixture.seed(), TIMEOUT, null), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    var entered = new AtomicBoolean();
    assertCode(() -> backend.read(fixture.parent, List.of(fixture.child), access, TIMEOUT, session -> {
      entered.set(true);
      return session.snapshot(operation);
    }), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertThat(entered).isFalse();
    var childQuery = new RecordSelectPlan("SELECT COUNT(*) FROM \"runtime\".\"patch_children\"", List.of(), List.of(), 1);
    assertCode(() -> backend.query(fixture.child, key.tenantId(), childQuery, TIMEOUT), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertCode(() -> backend.publishWritePolicy(fixture.parent, access.actor(), intent.command(), intent.fingerprint(), Map.of(), Map.of(),
        new RecordExecutionDeadline(TIMEOUT)), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    assertCode(() -> backend.cancelWritePolicy(fixture.parent, access.actor(), intent.command(), intent.fingerprint(), "e".repeat(64),
        new RecordExecutionDeadline(TIMEOUT)), DnaRuntimeErrorCode.RECORD_MAINTENANCE_IN_PROGRESS);
    var otherTenant = new RecordSelectPlan(query.sql(), List.of("tenant-b"), List.of(), 1);
    assertThat(((Number) backend.query(fixture.parent, "tenant-b", otherTenant, TIMEOUT).rows().getFirst().getFirst()).longValue()).isZero();
    try (var connection = locked(db)) {
      assertThat(db.fence().admitRead(connection, key, TIMEOUT).revision()).isZero();
      connection.rollback();
    }
  }

  private static RecordPublicationStore.Entry advance(Database db, RecordPublicationStore.Entry previous,
      RecordPublicationCheckpoint next, boolean commit) throws Exception {
    try (var connection = locked(db)) {
      assertThat(update(db, connection, JOURNAL.advance(previous, next, previous.intent().clock()))).isEqualTo(1);
      if (commit) connection.commit();
      else connection.rollback();
    }
    return new RecordPublicationStore.Entry(previous.intent(), next);
  }

  private static void assertCode(Runnable action, DnaRuntimeErrorCode code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Reader was not released");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }
}
