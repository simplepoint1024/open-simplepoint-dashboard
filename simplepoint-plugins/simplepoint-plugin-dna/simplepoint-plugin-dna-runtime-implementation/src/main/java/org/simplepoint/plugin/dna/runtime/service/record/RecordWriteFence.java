package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceSql;

/**
 * Target-transaction admission primitive. The caller owns commit/rollback and must use the same
 * physical connection for the protected Calcite operations. It never commits, creates tables,
 * reads business values, or releases a lock before the surrounding transaction ends.
 * Native SQL here is limited to platform lock metadata, whose FOR SHARE/FOR UPDATE semantics
 * are supplied by the tested database dialect; business SQL remains in the Calcite backend.
 */
final class RecordWriteFence {

  private static final String INITIAL_FINGERPRINT = "0".repeat(64);
  private final RecordWriteFenceSql sql;

  RecordWriteFence(final RecordWriteFenceSql sql) {
    this.sql = Objects.requireNonNull(sql, "sql");
  }

  /** Authorized provisioning only. An existing row's revision/fingerprint is never reset. */
  Snapshot initialize(final Connection connection, final RecordWriteFenceKey key,
      final String actorId, final int timeoutMs) throws SQLException {
    requireTransaction(connection, timeoutMs);
    requireActor(actorId);
    try (PreparedStatement statement = prepare(connection, sql.initialize(), timeoutMs)) {
      Timestamp now = Timestamp.from(Instant.now());
      statement.setString(1, key.id());
      statement.setString(2, key.tenantId());
      statement.setString(3, actorId);
      statement.setTimestamp(4, now);
      statement.setString(5, actorId);
      statement.setTimestamp(6, now);
      statement.setString(7, key.modelId());
      statement.setString(8, INITIAL_FINGERPRINT);
      statement.executeUpdate();
    }
    return lock(connection, key, true, timeoutMs);
  }

  /** Multiple writers may hold this shared lock; publication cannot advance until they end. */
  void admitWrite(final Connection connection, final RecordWriteFenceKey key,
      final long expectedRevision, final int timeoutMs) throws SQLException {
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("Write revision must not be negative");
    }
    if (lock(connection, key, false, timeoutMs).revision() != expectedRevision) {
      throw conflict();
    }
  }

  /**
   * Runs an impact check after earlier writers drain, then advances admission in the same
   * transaction. A repeated committed transition returns without rechecking mutable data.
   * The callback must inspect through the supplied connection and must not commit or rollback it.
   */
  Snapshot publish(final Connection connection, final RecordWriteFenceKey key,
      final long expectedRevision, final long targetRevision, final String fingerprint,
      final String actorId, final int timeoutMs, final Inspection inspection) throws SQLException {
    return publish(connection, key, expectedRevision, targetRevision, fingerprint, actorId,
        new RecordExecutionDeadline(timeoutMs), inspection);
  }

  Snapshot publish(final Connection connection, final RecordWriteFenceKey key,
      final long expectedRevision, final long targetRevision, final String fingerprint,
      final String actorId, final RecordExecutionDeadline deadline, final Inspection inspection) throws SQLException {
    requireActor(actorId);
    Objects.requireNonNull(inspection, "inspection");
    if (expectedRevision < 0 || targetRevision <= expectedRevision
        || fingerprint == null || !fingerprint.matches("[A-Fa-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid write-fence publication identity");
    }
    String canonical = fingerprint.toLowerCase(Locale.ROOT);
    Snapshot current = lock(connection, key, true, deadline.remainingMillis());
    if (current.revision() == targetRevision && current.previousRevision() == expectedRevision
        && current.fingerprint().equals(canonical)) {
      return current;
    }
    if (current.revision() != expectedRevision) {
      throw conflict();
    }
    inspection.inspect(RuntimeJdbcConnection.borrow(connection));
    return advance(connection, key, expectedRevision, targetRevision, canonical, actorId, deadline);
  }

  /**
   * Consumes the same target version with a cancellation identity, without applying new rules.
   * The exclusive lock serializes this with any in-flight publication or earlier business write.
   */
  Cancellation cancel(final Connection connection, final RecordWriteFenceKey key,
      final long expectedRevision, final long targetRevision, final String publicationFingerprint,
      final String cancellationFingerprint, final String actorId, final RecordExecutionDeadline deadline) throws SQLException {
    requireActor(actorId);
    if (expectedRevision < 0 || targetRevision <= expectedRevision || publicationFingerprint == null
        || !publicationFingerprint.matches("[a-f0-9]{64}") || cancellationFingerprint == null
        || !cancellationFingerprint.matches("[a-f0-9]{64}") || cancellationFingerprint.equals(publicationFingerprint)) {
      throw new IllegalArgumentException("Invalid write-fence cancellation identity");
    }
    Snapshot current = lock(connection, key, true, deadline.remainingMillis());
    if (current.revision() == targetRevision && current.previousRevision() == expectedRevision) {
      if (current.fingerprint().equals(publicationFingerprint)) {
        return new Cancellation(false, current);
      }
      if (current.fingerprint().equals(cancellationFingerprint)) {
        return new Cancellation(true, current);
      }
    }
    if (current.revision() != expectedRevision) {
      throw conflict();
    }
    return new Cancellation(true, advance(connection, key, expectedRevision, targetRevision, cancellationFingerprint, actorId, deadline));
  }

  private Snapshot advance(final Connection connection, final RecordWriteFenceKey key, final long expectedRevision,
      final long targetRevision, final String canonical, final String actorId, final RecordExecutionDeadline deadline) throws SQLException {
    requireTransaction(connection, deadline.remainingMillis());
    try (PreparedStatement statement = prepare(connection, sql.advance(), deadline.remainingMillis())) {
      statement.setLong(1, targetRevision);
      statement.setString(2, canonical);
      statement.setLong(3, expectedRevision);
      statement.setString(4, actorId);
      statement.setTimestamp(5, Timestamp.from(Instant.now()));
      identity(statement, 6, key);
      statement.setLong(9, expectedRevision);
      if (statement.executeUpdate() != 1) {
        throw conflict();
      }
    }
    return new Snapshot(targetRevision, expectedRevision, canonical);
  }

  private Snapshot lock(final Connection connection, final RecordWriteFenceKey key,
      final boolean exclusive, final int timeoutMs) throws SQLException {
    requireTransaction(connection, timeoutMs);
    try (PreparedStatement statement = prepare(connection, exclusive ? sql.lockExclusive() : sql.lockShared(), timeoutMs)) {
      identity(statement, 1, key);
      try (var result = statement.executeQuery()) {
        if (!result.next()) {
          throw unavailable();
        }
        long revision = result.getLong(1);
        if (result.wasNull() || revision < 0) {
          throw unavailable();
        }
        String fingerprint = result.getString(2);
        long previous = result.getLong(3);
        if (result.wasNull() || previous < 0 || (revision == 0 ? previous != 0 : previous >= revision)
            || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}") || result.next()) {
          throw unavailable();
        }
        return new Snapshot(revision, previous, fingerprint);
      }
    }
  }

  private static PreparedStatement prepare(final Connection connection, final String query,
      final int timeoutMs) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(query);
    try {
      statement.setQueryTimeout(Math.max(1, (timeoutMs + 999) / 1000));
      return statement;
    } catch (SQLException failure) {
      try {
        statement.close();
      } catch (SQLException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  private static void identity(final PreparedStatement statement, final int start,
      final RecordWriteFenceKey key) throws SQLException {
    statement.setString(start, key.id());
    statement.setString(start + 1, key.tenantId());
    statement.setString(start + 2, key.modelId());
  }

  private static void requireTransaction(final Connection connection, final int timeoutMs) throws SQLException {
    if (timeoutMs < 1 || timeoutMs > 120_000) {
      throw new IllegalArgumentException("Write fence timeout is invalid");
    }
    // A snapshot opened before the exclusive lock could miss a writer which has just drained.
    if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalArgumentException("Write fences require an explicit READ_COMMITTED physical transaction");
    }
  }

  private static void requireActor(final String actorId) {
    if (actorId == null || actorId.isBlank() || actorId.length() > 64) {
      throw new IllegalArgumentException("A bounded publication actor is required");
    }
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_CONFLICT,
        "Record write policy changed; reload the active policy before starting a new write");
  }

  private static DnaRuntimeException unavailable() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Record write-fence infrastructure is missing or inconsistent");
  }

  record Snapshot(long revision, long previousRevision, String fingerprint) {
  }

  record Cancellation(boolean cancelled, Snapshot snapshot) {
  }

  @FunctionalInterface
  interface Inspection {
    void inspect(Connection connection) throws SQLException;
  }
}
