package org.simplepoint.plugin.dna.federation.service.support;

import java.sql.Connection;
import java.sql.SQLException;
import org.simplepoint.data.calcite.core.jdbc.ScopedJdbcDataSource;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;

/** One operation owns its connection, and only a catalog-remapped operation owns its pool. */
final class FederationWriteDataSource extends SimpleDataSource {
  private final ScopedJdbcDataSource scope;
  private final SimpleDataSource ownedPool;
  private boolean closed;

  private FederationWriteDataSource(ScopedJdbcDataSource scope, SimpleDataSource ownedPool) {
    super(scope);
    this.scope = scope;
    this.ownedPool = ownedPool;
  }

  static FederationWriteDataSource pin(SimpleDataSource source, boolean ownsPool)
      throws SQLException {
    try {
      return new FederationWriteDataSource(
          new ScopedJdbcDataSource(source.getConnection()), ownsPool ? source : null);
    } catch (SQLException | RuntimeException failure) {
      if (ownsPool) {
        try {
          source.close();
        } catch (RuntimeException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      throw failure;
    }
  }

  Connection ownedConnection() throws SQLException {
    return scope.ownedConnection();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    RuntimeException failure = null;
    try {
      scope.close();
    } catch (SQLException | RuntimeException exception) {
      failure = new IllegalStateException("Physical write session cleanup failed", exception);
    } finally {
      if (ownedPool != null) {
        try {
          ownedPool.close();
        } catch (RuntimeException exception) {
          if (failure == null) {
            failure = exception;
          } else {
            failure.addSuppressed(exception);
          }
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
