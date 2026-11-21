package org.simplepoint.data.calcite.core.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Database-enforced read transactions for admitted query SQL on PostgreSQL and MySQL. Each borrow
 * owns one transaction; close rolls back before returning the connection. This does not admit SQL
 * grammar, sandbox database extensions or permit caller-controlled session commands/reconnects.
 */
public final class ReadOnlyJdbcDataSource implements DataSource {
  private static final int CONTROL_TIMEOUT_SECONDS = 10;
  private final DataSource delegate;

  /** Wraps an externally owned pool without changing its defaults or lifetime. */
  public ReadOnlyJdbcDataSource(DataSource delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /** Dialects verified by this connection protocol, not a claim about arbitrary JDBC drivers. */
  public static boolean supports(String databaseProduct) {
    return "PostgreSQL".equals(databaseProduct) || "MySQL".equals(databaseProduct);
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection connection = delegate.getConnection();
    try {
      if (!connection.getAutoCommit()) {
        throw new SQLException("Query scope cannot adopt an unfinished transaction");
      }
      String product = connection.getMetaData().getDatabaseProductName();
      if (!supports(product)) {
        throw new SQLFeatureNotSupportedException(
            "Database read transaction protocol is unavailable");
      }
      boolean mysql = "MySQL".equals(product);
      final boolean originalReadOnly = connection.isReadOnly();
      final boolean originalServerReadOnly = mysql && mysqlReadOnly(connection);
      connection.setReadOnly(true);
      if (mysql) {
        // Server mode is explicit even when Connector/J readOnlyPropagatesToServer is disabled.
        // SESSION also replaces an unconsumed next-transaction override on this fresh borrow.
        execute(connection, "SET SESSION TRANSACTION READ ONLY");
        if (!mysqlReadOnly(connection)) {
          throw new SQLException("Database did not enable read-only transactions");
        }
      }
      connection.setAutoCommit(false);
      if (!mysql) {
        // JDBC may treat readOnly as a hint. The explicit transaction setting is authoritative.
        execute(connection, "SET TRANSACTION READ ONLY");
        try (var statement = connection.createStatement()) {
          statement.setQueryTimeout(CONTROL_TIMEOUT_SECONDS);
          try (var rows =
              statement.executeQuery(
                  "SELECT pg_catalog.current_setting('transaction_read_only')")) {
            if (!rows.next() || !"on".equals(rows.getString(1)) || rows.next()) {
              throw new SQLException("Database did not enable a read-only transaction");
            }
          }
        }
      }
      var scope =
          new ScopedJdbcDataSource(
              connection,
              physical -> {
                // Never restore autocommit or return to a pool after an uncertain rollback.
                physical.rollback();
                physical.setAutoCommit(true);
                physical.setReadOnly(originalReadOnly);
                if (mysql) {
                  execute(
                      physical,
                      originalServerReadOnly
                          ? "SET SESSION TRANSACTION READ ONLY"
                          : "SET SESSION TRANSACTION READ WRITE");
                }
              });
      return scope.owningLease();
    } catch (SQLException | RuntimeException failure) {
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException abortFailure) {
        failure.addSuppressed(abortFailure);
      }
      throw failure;
    }
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    throw new SQLFeatureNotSupportedException(
        "Query scopes use the configured datasource identity");
  }

  private static boolean mysqlReadOnly(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(CONTROL_TIMEOUT_SECONDS);
      try (var rows = statement.executeQuery("SELECT @@SESSION.transaction_read_only")) {
        if (!rows.next()) {
          throw new SQLException("Database read-only state is unavailable");
        }
        boolean value = rows.getBoolean(1);
        if (rows.wasNull() || rows.next()) {
          throw new SQLException("Database read-only state is invalid");
        }
        return value;
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(CONTROL_TIMEOUT_SECONDS);
      statement.execute(sql);
    }
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter writer) throws SQLException {
    throw new SQLFeatureNotSupportedException("The pool owner controls connection settings");
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    throw new SQLFeatureNotSupportedException("The pool owner controls connection settings");
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  @Override
  public <T> T unwrap(Class<T> type) throws SQLException {
    throw new SQLException("Cannot unwrap a read-only datasource");
  }

  @Override
  public boolean isWrapperFor(Class<?> type) {
    return false;
  }
}
