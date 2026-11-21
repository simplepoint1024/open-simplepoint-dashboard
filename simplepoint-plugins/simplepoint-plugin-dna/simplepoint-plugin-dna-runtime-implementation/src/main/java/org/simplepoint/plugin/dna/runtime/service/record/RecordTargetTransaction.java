package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;

/** One owner for the physical transaction used by writes, admission preparation and publication. */
final class RecordTargetTransaction {

  private static final int MAX_ATTEMPTS = 3;

  private RecordTargetTransaction() {}

  static <T> T execute(final ManagedRuntimeDataSource handle, final Work<T> work)
      throws SQLException {
    return attempt(handle, work, false).result();
  }

  /**
   * Only transaction-local work with immutable input may opt in; never retries a commit attempt.
   */
  static <T> T executeRetrying(
      final ManagedRuntimeDataSource handle,
      final RecordExecutionDeadline deadline,
      final Work<T> work)
      throws SQLException {
    for (int number = 1; ; number++) {
      deadline.remainingMillis();
      var outcome =
          attempt(
              handle,
              connection -> {
                T result = work.execute(connection);
                deadline.remainingMillis();
                return result;
              },
              true);
      if (outcome.contention() == null) {
        return outcome.result();
      }
      Exception failure = outcome.contention();
      if (number == MAX_ATTEMPTS) {
        if (failure instanceof SQLException sql) {
          throw sql;
        }
        throw (RuntimeException) failure;
      }
      // The connection is closed and rollback acknowledged before backoff or opening another one.
      try {
        int delay = ThreadLocalRandom.current().nextInt(10 * number, 30 * number + 1);
        Thread.sleep(Math.min(delay, deadline.remainingMillis()));
        deadline.remainingMillis();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        var cancelled =
            new DnaRuntimeException(
                DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
                "Record contention retry was interrupted",
                interrupted);
        cancelled.addSuppressed(failure);
        throw cancelled;
      } catch (DnaRuntimeException expired) {
        expired.addSuppressed(failure);
        throw expired;
      }
    }
  }

  private static <T> Attempt<T> attempt(
      final ManagedRuntimeDataSource handle, final Work<T> work, final boolean retryContention)
      throws SQLException {
    try (Connection physical = handle.dataSource().getConnection()) {
      boolean autoCommit = physical.getAutoCommit();
      int isolation = physical.getTransactionIsolation();
      boolean ended = false;
      boolean commitAttempted = false;
      try {
        physical.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        physical.setAutoCommit(false);
        handle.runtimeDialect().prepareRecordTransaction(physical);
        final T result = work.execute(physical);
        commitAttempted = true;
        physical.commit();
        ended = true;
        return new Attempt<>(result, null);
      } catch (RuntimeException | SQLException failure) {
        ended = rollback(physical, failure);
        if (retryContention
            && ended
            && !commitAttempted
            && RecordContentionFailures.retryable(handle.runtimeDialect().dialectId(), failure)) {
          return new Attempt<>(null, failure);
        }
        throw failure;
      } finally {
        if (ended) {
          restore(physical, autoCommit, isolation);
        }
      }
    }
  }

  private record Attempt<T>(T result, Exception contention) {}

  private static boolean rollback(final Connection connection, final Throwable failure) {
    try {
      connection.rollback();
      return true;
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
      // Restoring autocommit after an unsuccessful rollback could commit partial work.
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException abortFailure) {
        failure.addSuppressed(abortFailure);
      }
      return false;
    }
  }

  private static void restore(
      final Connection connection, final boolean autoCommit, final int isolation) {
    try {
      if (connection.getTransactionIsolation() != isolation) {
        connection.setTransactionIsolation(isolation);
      }
      connection.setAutoCommit(autoCommit);
    } catch (SQLException ignored) {
      // Closing the physical connection is the final boundary after the transaction ended.
    }
  }

  @FunctionalInterface
  interface Work<T> {
    T execute(Connection connection) throws SQLException;
  }
}
