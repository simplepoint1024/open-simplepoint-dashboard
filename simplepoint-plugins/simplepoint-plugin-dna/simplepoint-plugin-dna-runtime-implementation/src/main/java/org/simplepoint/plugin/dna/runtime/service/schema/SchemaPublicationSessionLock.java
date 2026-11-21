package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Session lock on the very connection executing DDL; connection loss also removes the executor. */
final class SchemaPublicationSessionLock implements AutoCloseable {
  private final Connection connection;
  private final boolean postgres;
  private final String key;

  private SchemaPublicationSessionLock(Connection connection, boolean postgres, String key) {
    this.connection = connection;
    this.postgres = postgres;
    this.key = key;
  }

  static SchemaPublicationSessionLock acquire(Connection connection, String dialect, String key) throws SQLException {
    if (!"postgresql".equals(dialect) && !"mysql".equals(dialect)) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.DIALECT_OPERATION_UNSUPPORTED,
          "The schema dialect has no exclusive publication session implementation");
    }
    var lock = new SchemaPublicationSessionLock(connection, "postgresql".equals(dialect), key);
    try {
      if (!lock.invoke(true)) {
        throw new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_MIGRATION_CONFLICT,
            "Another executor still owns the target database publication session");
      }
    } catch (SQLException failure) {
      // A lost acquisition reply cannot prove the server did not acquire this session lock.
      lock.abort(failure);
      throw failure;
    }
    return lock;
  }

  private boolean invoke(boolean acquire) throws SQLException {
    String sql = postgres
        ? (acquire ? "SELECT pg_try_advisory_lock(?)" : "SELECT pg_advisory_unlock(?)")
        : (acquire ? "SELECT GET_LOCK(?, 0)" : "SELECT RELEASE_LOCK(?)");
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(30);
      if (postgres) statement.setLong(1, Long.parseUnsignedLong(key.substring(0, 16), 16));
      else statement.setString(1, key);
      try (var rows = statement.executeQuery()) {
        return rows.next() && rows.getBoolean(1) && !rows.wasNull();
      }
    }
  }

  @Override
  public void close() throws SQLException {
    try {
      if (!invoke(false)) throw new SQLException("Publication session lock ownership was lost");
    } catch (SQLException failure) {
      // A pooled connection must never retain this lock after the operation exits.
      abort(failure);
      throw failure;
    }
  }

  private void abort(SQLException failure) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException abortFailure) {
      failure.addSuppressed(abortFailure);
    }
  }
}
