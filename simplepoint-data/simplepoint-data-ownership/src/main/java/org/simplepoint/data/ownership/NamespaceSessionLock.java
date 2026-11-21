package org.simplepoint.data.ownership;

import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.SESSION_BUSY;
import static org.simplepoint.data.ownership.PhysicalOwnershipException.Code.SESSION_STATE_INVALID;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Session-scoped exclusion survives MySQL DDL commits and is held by the actual executing session.
 */
final class NamespaceSessionLock implements AutoCloseable {
  private final Connection connection;
  private final PhysicalNamespace namespace;
  private final NativeSessionConnection session;
  private boolean closed;

  private NamespaceSessionLock(
      Connection connection, PhysicalNamespace namespace, NativeSessionConnection session) {
    this.connection = connection;
    this.namespace = namespace;
    this.session = session;
  }

  static NamespaceSessionLock acquire(Connection connection, PhysicalNamespace namespace)
      throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
    }
    var session = NativeSessionConnection.capture(connection, namespace.postgres);
    var lock = new NamespaceSessionLock(connection, namespace, session);
    boolean acquired;
    try {
      acquired = lock.invoke(true);
      session.verify(connection);
    } catch (SQLException | RuntimeException failure) {
      abort(connection, failure);
      throw failure;
    }
    if (!acquired) {
      throw new PhysicalOwnershipException(SESSION_BUSY);
    }
    return lock;
  }

  private boolean invoke(boolean acquire) throws SQLException {
    String sql =
        namespace.postgres
            ? (acquire
                ? "SELECT pg_catalog.pg_try_advisory_lock(?)"
                : "SELECT pg_catalog.pg_advisory_unlock(?)")
            : (acquire ? "SELECT GET_LOCK(?, 0)" : "SELECT RELEASE_LOCK(?)");
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      String key = namespace.lockKey();
      if (namespace.postgres) {
        statement.setLong(1, Long.parseUnsignedLong(key.substring(0, 16), 16));
      } else {
        statement.setString(1, key);
      }
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("Physical namespace lock returned no receipt");
        }
        boolean result = rows.getBoolean(1);
        if (rows.wasNull() || rows.next()) {
          throw new SQLException("Physical namespace lock returned an invalid receipt");
        }
        return result;
      }
    }
  }

  void requireHeld() throws SQLException {
    if (closed) {
      throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
    }
    try {
      session.verify(connection);
    } catch (SQLException | RuntimeException failure) {
      abort(connection, failure);
      throw failure;
    }
    String sql =
        namespace.postgres
            ? "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks WHERE locktype='advisory' AND"
                + " pid=pg_catalog.pg_backend_pid() AND granted AND mode='ExclusiveLock' AND"
                + " objsubid=1 AND classid=CAST(? AS pg_catalog.oid) AND objid=CAST(? AS"
                + " pg_catalog.oid))"
            : "SELECT IS_USED_LOCK(?) = CONNECTION_ID()";
    try (var statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(10);
      if (namespace.postgres) {
        long key = Long.parseUnsignedLong(namespace.lockKey().substring(0, 16), 16);
        statement.setLong(1, key >>> 32);
        statement.setLong(2, key & 0xffffffffL);
      } else {
        statement.setString(1, namespace.lockKey());
      }
      try (var rows = statement.executeQuery()) {
        if (!rows.next() || !rows.getBoolean(1) || rows.wasNull() || rows.next()) {
          throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
        }
      }
    } catch (SQLException | RuntimeException failure) {
      abort(connection, failure);
      throw failure;
    }
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (!connection.getAutoCommit()) {
        throw new PhysicalOwnershipException(SESSION_STATE_INVALID);
      }
      session.verify(connection);
      if (!invoke(false)) {
        throw new SQLException("Physical namespace lock ownership was lost");
      }
    } catch (SQLException | RuntimeException failure) {
      abort(connection, failure);
      throw failure;
    }
  }

  static void abort(Connection connection, Throwable failure) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException abortFailure) {
      failure.addSuppressed(abortFailure);
    }
  }
}
