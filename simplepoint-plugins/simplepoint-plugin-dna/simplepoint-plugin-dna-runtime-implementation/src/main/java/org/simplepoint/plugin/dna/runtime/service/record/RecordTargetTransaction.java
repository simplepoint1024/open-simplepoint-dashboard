package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Connection;
import java.sql.SQLException;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;

/** One owner for the physical transaction used by writes, admission preparation and publication. */
final class RecordTargetTransaction {

  private RecordTargetTransaction() {
  }

  static <T> T execute(final ManagedRuntimeDataSource handle, final Work<T> work) throws SQLException {
    try (Connection physical = handle.dataSource().getConnection()) {
      boolean autoCommit = physical.getAutoCommit();
      int isolation = physical.getTransactionIsolation();
      boolean ended = false;
      try {
        physical.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        physical.setAutoCommit(false);
        handle.runtimeDialect().prepareRecordTransaction(physical);
        T result = work.execute(physical);
        physical.commit();
        ended = true;
        return result;
      } catch (RuntimeException | SQLException failure) {
        ended = rollback(physical, failure);
        throw failure;
      } finally {
        if (ended) {
          restore(physical, autoCommit, isolation);
        }
      }
    }
  }

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

  private static void restore(final Connection connection, final boolean autoCommit, final int isolation) {
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
