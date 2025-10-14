package org.simplepoint.data.datasource.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A simple implementation of the DataSource interface.
 * This class provides a basic implementation for obtaining database connections
 * and wraps a delegate DataSource for customization or additional functionality.
 */
public class SimpleDataSource implements DataSource {

  private final DataSource delegate;

  /**
   * Constructs a new SimpleDataSource instance.
   * This constructor initializes the instance with a delegate DataSource.
   *
   * @param delegate the DataSource instance to delegate calls to
   */
  public SimpleDataSource(DataSource delegate) {
    this.delegate = delegate;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return new SimpleConnection(delegate.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return new SimpleConnection(delegate.getConnection(username, password));
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    delegate.setLogWriter(out);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return delegate.isWrapperFor(iface);
  }
}
