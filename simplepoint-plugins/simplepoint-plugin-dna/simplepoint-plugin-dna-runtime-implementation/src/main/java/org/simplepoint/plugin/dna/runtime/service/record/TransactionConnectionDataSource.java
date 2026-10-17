package org.simplepoint.plugin.dna.runtime.service.record;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** DataSource view whose borrowed connection belongs to this backend transaction. */
final class TransactionConnectionDataSource implements DataSource {

  private final Connection connection;

  TransactionConnectionDataSource(final Connection connection) {
    this.connection = connection;
  }

  @Override
  public Connection getConnection() {
    return RuntimeJdbcConnection.borrow(connection);
  }

  @Override
  public Connection getConnection(final String username, final String password) {
    return getConnection();
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(final PrintWriter out) {
    // No independent pool is configured by this transaction-scoped adapter.
  }

  @Override
  public void setLoginTimeout(final int seconds) {
    // The underlying managed datasource owns connection acquisition policy.
  }

  @Override
  public int getLoginTimeout() {
    return 0;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public <T> T unwrap(final Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(final Class<?> iface) {
    return iface.isInstance(this);
  }

}
