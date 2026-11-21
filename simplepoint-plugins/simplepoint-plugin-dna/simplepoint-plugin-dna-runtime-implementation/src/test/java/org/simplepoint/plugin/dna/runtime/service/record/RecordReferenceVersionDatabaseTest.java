package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Target observations are rechecked after the shared source/target lock ordering on real DBs. */
class RecordReferenceVersionDatabaseTest {
  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void exactObservationCommitsStaleObservationFailsAndCommittedReplayKeepsItsOriginalResult(
      boolean postgres) throws Exception {
    try (var f = new RecordReferenceQualificationDatabaseTest.Fixture(postgres)) {
      String target = f.customer("ACTIVE");
      var create =
          f.base.create(f.base.trade, Map.of("customer_id", target, "note", "Observed v0"));
      var operation = operation(f);
      var committed =
          f.base.backend.executePreparedOwned(
              f.base.trade,
              List.of(),
              new RecordWriteAdmission("tenant-a", 1),
              30_000,
              operation,
              (reader, at, policy) -> prepared(f, create, target, 0));
      assertThat(committed.replayed()).isFalse();
      f.base.save(
          f.base.customer, f.base.update(f.base.customer, target, 0, Map.of("status", "ACTIVE")));
      var stale =
          f.base.create(f.base.trade, Map.of("customer_id", target, "note", "Must not commit"));
      conflict(
          () ->
              f.base.backend.executePreparedOwned(
                  f.base.trade,
                  List.of(),
                  new RecordWriteAdmission("tenant-a", 1),
                  30_000,
                  operation(f),
                  (reader, at, policy) -> prepared(f, stale, target, 0)));
      assertThat(f.base.count(f.base.trade)).isEqualTo(1);
      var replay =
          f.base.backend.executePreparedOwned(
              f.base.trade,
              List.of(),
              new RecordWriteAdmission("tenant-a", 1),
              30_000,
              operation,
              (reader, at, policy) -> {
                throw new AssertionError("An exact committed replay cannot recopy current data");
              });
      assertThat(replay.replayed()).isTrue();
      assertThat(replay.results()).isEqualTo(committed.results());
      assertThat(f.base.value(f.base.trade, create.recordId(), "note")).isEqualTo("Observed v0");
      f.authorize(false, "SELF", Map.of());
      conflict(
          () ->
              f.base.backend.executePreparedOwned(
                  f.base.trade,
                  List.of(),
                  new RecordWriteAdmission("tenant-a", 1),
                  30_000,
                  operation(f),
                  (reader, at, policy) -> prepared(f, stale, target, 1)));
      assertThat(f.base.count(f.base.trade)).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void updateBetweenPreparationAndParticipantLocksCannotCommitAnOldCopy(boolean postgres)
      throws Exception {
    try (var f = new RecordReferenceQualificationDatabaseTest.Fixture(postgres);
        var workers = Executors.newSingleThreadExecutor()) {
      String target = f.customer("ACTIVE");
      var create = f.base.create(f.base.trade, Map.of("customer_id", target, "note", "Old source"));
      var prepared = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var write =
          workers.submit(
              () -> {
                f.authorize(true, "SELF", Map.of());
                try {
                  return f.base.backend.executePreparedOwned(
                      f.base.trade,
                      List.of(),
                      new RecordWriteAdmission("tenant-a", 1),
                      30_000,
                      operation(f),
                      (reader, at, policy) -> {
                        prepared.countDown();
                        try {
                          if (!release.await(20, TimeUnit.SECONDS)) {
                            throw new AssertionError("Preparation was not released");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(interrupted);
                        }
                        return prepared(f, create, target, 0);
                      });
                } finally {
                  RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
                  ResourcePolicyContext.clear();
                }
              });
      try {
        assertThat(prepared.await(20, TimeUnit.SECONDS)).isTrue();
        // The target remains eligible; only its version changed. Qualification alone would pass.
        f.base.save(
            f.base.customer, f.base.update(f.base.customer, target, 0, Map.of("status", "ACTIVE")));
        release.countDown();
        assertThatThrownBy(() -> write.get(25, TimeUnit.SECONDS))
            .hasCauseInstanceOf(DnaRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((DnaRuntimeException) failure.getCause()).errorCode())
                        .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
        assertThat(f.base.count(f.base.trade)).isZero();
      } finally {
        release.countDown();
      }
    }
  }

  private static RecordWritePreparation prepared(
      RecordReferenceQualificationDatabaseTest.Fixture f,
      RecordSqlStatement statement,
      String target,
      long version) {
    return new RecordWritePreparation(
        List.of(statement),
        null,
        1,
        List.of(
            new RecordReferenceCheck(
                "trade-customer",
                f.base.trade.physicalModelId(),
                statement.recordId(),
                target,
                true,
                version)));
  }

  private static RecordOperationIdentity operation(
      RecordReferenceQualificationDatabaseTest.Fixture f) {
    return new RecordOperationIdentity(
        UUID.randomUUID().toString(),
        "tenant-a",
        "user",
        f.base.trade.physicalModelId(),
        UUID.randomUUID().toString(),
        "a".repeat(64));
  }

  private static void conflict(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
  }
}
