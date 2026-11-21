package org.simplepoint.data.calcite.core.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.calcite.avatica.util.ByteString;

/**
 * Owns one physical connection for a bounded Calcite operation. JDBC borrowers cannot change its
 * transaction or namespace, close it, unwrap it, or use it after their lease or this scope ends.
 */
public final class ScopedJdbcDataSource implements DataSource, AutoCloseable {
  private static final Set<String> OWNER_METHODS =
      Set.of(
          "commit",
          "rollback",
          "setAutoCommit",
          "abort",
          "setTransactionIsolation",
          "setReadOnly",
          "setCatalog",
          "setSchema",
          "setSavepoint",
          "releaseSavepoint",
          "setNetworkTimeout",
          "setHoldability",
          "setTypeMap",
          "beginRequest",
          "endRequest",
          "setShardingKey",
          "setShardingKeyIfValid");
  private final Connection physical;
  private final ConnectionCleanup cleanup;
  private volatile boolean closed;

  /**
   * Transfers physical connection ownership to this scope; caller retains transaction ownership.
   */
  public ScopedJdbcDataSource(Connection physical) {
    this(physical, connection -> {});
  }

  ScopedJdbcDataSource(Connection physical, ConnectionCleanup cleanup) {
    this.physical = Objects.requireNonNull(physical, "physical");
    this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
  }

  /** Owner-only access for admission/locking on the same connection used by the JDBC adapter. */
  public synchronized Connection ownedConnection() throws SQLException {
    requireOpen();
    return physical;
  }

  @Override
  public synchronized Connection getConnection() throws SQLException {
    return lease(false);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    throw new SQLFeatureNotSupportedException("A scoped connection cannot change credentials");
  }

  synchronized Connection owningLease() throws SQLException {
    return lease(true);
  }

  private Connection lease(boolean ownsScope) throws SQLException {
    requireOpen();
    var lease = new Lease();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              synchronized (this) {
                if (method.getDeclaringClass() == Object.class) {
                  return objectMethod(proxy, method, args);
                }
                String name = method.getName();
                if (name.equals("close")) {
                  lease.closed = true;
                  if (ownsScope) {
                    close();
                  }
                  return null;
                }
                if (name.equals("isClosed")) {
                  return closed || lease.closed || physical.isClosed();
                }
                if (name.equals("setClientInfo")) {
                  throw new java.sql.SQLClientInfoException();
                }
                requireLease(lease);
                if (OWNER_METHODS.contains(name)) {
                  throw new SQLException("The execution owner controls the physical session");
                }
                if (wrapperMethod(method)) {
                  return wrapper(proxy, method, args);
                }
                Object value = invoke(physical, method, args);
                if (value instanceof Statement statement) {
                  return statement(statement, (Connection) proxy, lease);
                }
                if (value instanceof DatabaseMetaData metadata) {
                  return metadata(metadata, (Connection) proxy, lease);
                }
                return value instanceof Array array
                    ? array(array, (Connection) proxy, lease)
                    : value;
              }
            });
  }

  private Statement statement(Statement delegate, Connection connection, Lease lease) {
    Class<?> type =
        delegate instanceof CallableStatement
            ? CallableStatement.class
            : delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              // A query holds the scope monitor while executing. Cancellation must be able to
              // reach the driver from another thread, without permitting transaction changes.
              if (method.getName().equals("cancel")) {
                requireLease(lease);
                delegate.cancel();
                return null;
              }
              synchronized (this) {
                if (method.getDeclaringClass() == Object.class) {
                  return objectMethod(proxy, method, args);
                }
                if (method.getName().equals("close")) {
                  if (!closed) {
                    delegate.close();
                  }
                  return null;
                }
                if (method.getName().equals("isClosed")) {
                  return closed || lease.closed || delegate.isClosed();
                }
                requireLease(lease);
                if (method.getName().equals("getConnection")) {
                  return connection;
                }
                if (wrapperMethod(method)) {
                  return wrapper(proxy, method, args);
                }
                if (delegate instanceof PreparedStatement
                    && method.getName().equals("setObject")
                    && args != null
                    && args.length >= 2
                    && args[1] instanceof ByteString bytes) {
                  args = args.clone();
                  args[1] = bytes.getBytes();
                }
                Object value = invoke(delegate, method, args);
                return value instanceof ResultSet rows
                    ? rows(rows, (Statement) proxy, connection, lease)
                    : value;
              }
            });
  }

  private DatabaseMetaData metadata(DatabaseMetaData delegate, Connection connection, Lease lease) {
    return (DatabaseMetaData)
        Proxy.newProxyInstance(
            DatabaseMetaData.class.getClassLoader(),
            new Class<?>[] {DatabaseMetaData.class},
            (proxy, method, args) -> {
              synchronized (this) {
                if (method.getDeclaringClass() == Object.class) {
                  return objectMethod(proxy, method, args);
                }
                requireLease(lease);
                if (method.getName().equals("getConnection")) {
                  return connection;
                }
                if (wrapperMethod(method)) {
                  return wrapper(proxy, method, args);
                }
                Object value = invoke(delegate, method, args);
                if (value instanceof ResultSet rows) {
                  var owner = rows.getStatement();
                  return rows(
                      rows,
                      owner == null ? null : statement(owner, connection, lease),
                      connection,
                      lease);
                }
                return value;
              }
            });
  }

  private ResultSet rows(
      ResultSet delegate, Statement statement, Connection connection, Lease lease) {
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) -> {
              synchronized (this) {
                if (method.getDeclaringClass() == Object.class) {
                  return objectMethod(proxy, method, args);
                }
                if (method.getName().equals("close")) {
                  if (!closed) {
                    delegate.close();
                  }
                  return null;
                }
                if (method.getName().equals("isClosed")) {
                  return closed || lease.closed || delegate.isClosed();
                }
                requireLease(lease);
                if (method.getName().equals("getStatement")) {
                  return statement;
                }
                if (wrapperMethod(method)) {
                  return wrapper(proxy, method, args);
                }
                Object value = invoke(delegate, method, args);
                if (value instanceof Array array) {
                  return array(array, connection, lease);
                }
                if (value instanceof ResultSet nested) {
                  var owner = nested.getStatement();
                  return rows(
                      nested,
                      owner == null ? null : statement(owner, connection, lease),
                      connection,
                      lease);
                }
                return value;
              }
            });
  }

  private Array array(Array delegate, Connection connection, Lease lease) {
    return (Array)
        Proxy.newProxyInstance(
            Array.class.getClassLoader(),
            new Class<?>[] {Array.class},
            (proxy, method, args) -> {
              synchronized (this) {
                if (method.getDeclaringClass() == Object.class) {
                  return objectMethod(proxy, method, args);
                }
                if (method.getName().equals("free")) {
                  if (!closed) {
                    delegate.free();
                  }
                  return null;
                }
                requireLease(lease);
                Object value = invoke(delegate, method, args);
                if (value instanceof ResultSet rows) {
                  var owner = rows.getStatement();
                  return rows(
                      rows,
                      owner == null ? null : statement(owner, connection, lease),
                      connection,
                      lease);
                }
                return value;
              }
            });
  }

  @Override
  public synchronized void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      cleanup.close(physical);
      physical.close();
    } catch (SQLException | RuntimeException failure) {
      try {
        physical.abort(Runnable::run);
      } catch (SQLException | RuntimeException abortFailure) {
        failure.addSuppressed(abortFailure);
      }
      throw failure;
    }
  }

  private void requireOpen() throws SQLException {
    if (closed || physical.isClosed()) {
      throw new SQLException("The execution scope is closed");
    }
  }

  private void requireLease(Lease lease) throws SQLException {
    requireOpen();
    if (lease.closed) {
      throw new SQLException("The borrowed connection is closed");
    }
  }

  private static final class Lease {
    private volatile boolean closed;
  }

  @FunctionalInterface
  interface ConnectionCleanup {
    void close(Connection connection) throws SQLException;
  }

  private static Object objectMethod(Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "equals" -> proxy == args[0];
      case "hashCode" -> System.identityHashCode(proxy);
      default -> "Scoped JDBC " + proxy.getClass().getInterfaces()[0].getSimpleName();
    };
  }

  private static boolean wrapperMethod(Method method) {
    return method.getName().equals("unwrap") || method.getName().equals("isWrapperFor");
  }

  private static Object wrapper(Object proxy, Method method, Object[] args) throws SQLException {
    Class<?> type = (Class<?>) args[0];
    if (type == null) {
      throw new SQLException("Wrapper type is required");
    }
    if (method.getName().equals("isWrapperFor")) {
      return type.isInstance(proxy);
    }
    if (type.isInstance(proxy)) {
      return proxy;
    }
    throw new SQLException("Cannot unwrap a scoped JDBC object");
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter writer) throws SQLException {
    throw new SQLFeatureNotSupportedException("The execution owner controls connection settings");
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    throw new SQLFeatureNotSupportedException("The physical connection has already been acquired");
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
  public <T> T unwrap(Class<T> type) throws SQLException {
    // Do not expose the scope owner through a JDBC borrower's DataSource unwrap route.
    throw new SQLException("Cannot unwrap a scoped datasource");
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return false;
  }
}
