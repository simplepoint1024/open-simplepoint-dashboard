package org.simplepoint.plugin.dna.jdbc.driver;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;

/**
 * Concrete {@link Statement} implementation for the DNA JDBC driver.
 *
 * <p>This class replaces the former dynamic-proxy {@code StatementHandler}.
 * It is intentionally
 * <strong>not</strong> {@code final} so that {@code DnaJdbcPreparedStatement}
 * can extend it with parameter-binding logic.
 *
 * <p>Supports both read queries (SELECT), DML statements (INSERT, UPDATE,
 * DELETE, MERGE/UPSERT), and DDL statements (CREATE, ALTER, DROP, TRUNCATE).
 * DML and DDL are pushed directly to the physical database through the DNA
 * gateway without Calcite optimization.
 */
class DnaJdbcStatement implements Statement {

  protected final DnaJdbcConnection connection;

  private final int resultSetType;

  private final int resultSetConcurrency;

  private final int resultSetHoldability;

  private boolean closed;

  private int maxRows;

  private int queryTimeout;

  private int fetchSize;

  private boolean closeOnCompletion;

  protected ResultSet currentResultSet;

  private int lastUpdateCount = -1;

  private boolean lastExecuteWasQuery;

  DnaJdbcStatement(
      final DnaJdbcConnection connection,
      final int resultSetType,
      final int resultSetConcurrency,
      final int resultSetHoldability
  ) {
    this.connection = connection;
    this.resultSetType = resultSetType;
    this.resultSetConcurrency = resultSetConcurrency;
    this.resultSetHoldability = resultSetHoldability;
  }

  // ------------------------------------------------------------------
  // Protected helpers for subclasses
  // ------------------------------------------------------------------

  protected ResultSet doExecuteQuery(final String sql) throws SQLException {
    return doExecuteQuery(sql, null);
  }

  /**
   * Executes the given SQL query against the DNA gateway and returns the
   * resulting {@link ResultSet}.  Any previously open result set is closed
   * first.
   *
   * @param sql the SQL query to execute (must not be {@code null})
   * @param parameters bind parameters for server-side prepared execution (may be {@code null})
   * @return the query result set
   * @throws SQLException if a database access error occurs
   */
  protected ResultSet doExecuteQuery(final String sql, final List<Object> parameters) throws SQLException {
    closeCurrentResultSet();
    return withTimeoutGuard("查询", () -> {
      DnaJdbcModels.QueryResult result =
          connection.client().query(connection.currentCatalog(), sql, connection.currentSchema(), parameters);
      currentResultSet = ResultSetBuilder.fromQueryResult(result, maxRows);
      return currentResultSet;
    });
  }

  protected int doExecuteUpdate(final String sql) throws SQLException {
    return doExecuteUpdate(sql, null);
  }

  /**
   * Executes the given DML SQL against the DNA gateway and returns the
   * affected row count.
   *
   * @param sql the DML SQL to execute (INSERT / UPDATE / DELETE / MERGE)
   * @param parameters bind parameters for server-side prepared execution (may be {@code null})
   * @return affected row count
   * @throws SQLException if a database access error occurs
   */
  protected int doExecuteUpdate(final String sql, final List<Object> parameters) throws SQLException {
    closeCurrentResultSet();
    return withTimeoutGuard("执行", () -> {
      DnaJdbcModels.UpdateResult result =
          connection.client().executeUpdate(connection.currentCatalog(), sql, connection.currentSchema(), parameters);
      lastUpdateCount = result != null && result.affectedRows() != null ? result.affectedRows().intValue() : 0;
      return lastUpdateCount;
    });
  }

  protected int doExecuteDdl(final String sql) throws SQLException {
    return doExecuteDdl(sql, null);
  }

  /**
   * Executes a DDL statement (CREATE, ALTER, DROP, TRUNCATE, etc.)
   * by pushing it directly to the physical database through the gateway.
   *
   * @param sql the DDL SQL statement
   * @param parameters bind parameters for server-side prepared execution (may be {@code null})
   * @return affected rows (typically 0 for DDL)
   * @throws SQLException if execution fails
   */
  protected int doExecuteDdl(final String sql, final List<Object> parameters) throws SQLException {
    closeCurrentResultSet();
    return withTimeoutGuard("执行", () -> {
      DnaJdbcModels.UpdateResult result =
          connection.client().executeDdl(connection.currentCatalog(), sql, connection.currentSchema(), parameters);
      lastUpdateCount = result != null && result.affectedRows() != null ? result.affectedRows().intValue() : 0;
      return lastUpdateCount;
    });
  }

  /**
   * Applies the configured query timeout to the underlying socket, executes
   * the action, and restores the original timeout on completion.
   *
   * @param operationLabel label used in the timeout error message
   * @param action the callable to execute
   * @return the result of the action
   * @throws SQLException wrapping any timeout or execution error
   */
  private <T> T withTimeoutGuard(
      final String operationLabel,
      final SqlCallable<T> action
  ) throws SQLException {
    DnaJdbcClient client = connection.client();
    boolean timeoutAdjusted = false;
    if (queryTimeout > 0) {
      client.setSocketTimeout(queryTimeout * 1000);
      timeoutAdjusted = true;
    }
    try {
      return action.call();
    } catch (SQLException ex) {
      if (timeoutAdjusted && ex.getCause() instanceof java.net.SocketTimeoutException) {
        throw new SQLException(operationLabel + "超时 (" + queryTimeout + "s)", "HYT00", ex);
      }
      throw ex;
    } finally {
      if (timeoutAdjusted) {
        client.setSocketTimeout(0);
      }
    }
  }

  @FunctionalInterface
  private interface SqlCallable<T> {
    T call() throws SQLException;
  }

  /**
   * Closes the currently held result set, if any.
   *
   * @throws SQLException if closing the result set fails
   */
  protected void closeCurrentResultSet() throws SQLException {
    if (currentResultSet != null) {
      currentResultSet.close();
      currentResultSet = null;
    }
  }

  /**
   * Throws {@link SQLException} if this statement or its parent connection is
   * already closed.
   *
   * @throws SQLException if this statement is closed
   */
  protected void ensureOpen() throws SQLException {
    if (closed) {
      throw new SQLException("Statement 已关闭");
    }
    if (connection.isClosed()) {
      throw new SQLException("DNA JDBC 连接已关闭");
    }
  }

  /**
   * Resolves the SQL string to execute for a plain {@link Statement}.
   * Subclasses may override to support prepared-statement template rendering.
   *
   * @param sql the explicit SQL passed by the caller (may be {@code null})
   * @return the trimmed, non-null SQL string
   * @throws SQLException if the resolved SQL is blank or {@code null}
   */
  protected String resolveSql(final String sql) throws SQLException {
    String normalized = trimToNull(sql);
    if (normalized == null) {
      throw new SQLException("SQL 不能为空");
    }
    return normalized;
  }

  // ------------------------------------------------------------------
  // java.sql.Statement — query execution
  // ------------------------------------------------------------------

  @Override
  public ResultSet executeQuery(final String sql) throws SQLException {
    ensureOpen();
    return doExecuteQuery(resolveSql(sql));
  }

  private static final java.util.regex.Pattern FLUSH_CACHE_PATTERN =
      java.util.regex.Pattern.compile("\\s*FLUSH\\s+CACHE\\s*", java.util.regex.Pattern.CASE_INSENSITIVE);

  private static final java.util.regex.Pattern DML_PATTERN =
      java.util.regex.Pattern.compile("\\s*(INSERT|UPDATE|DELETE|MERGE|UPSERT)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

  static final java.util.regex.Pattern DDL_PATTERN =
      java.util.regex.Pattern.compile("\\s*(CREATE|ALTER|DROP|TRUNCATE|RENAME|COMMENT\\s+ON)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

  @Override
  public boolean execute(final String sql) throws SQLException {
    return execute(sql, (List<Object>) null);
  }

  /**
   * Executes the given SQL with optional server-side parameters.
   *
   * @param sql the SQL to execute
   * @param parameters bind parameters (may be {@code null} for non-prepared execution)
   * @return {@code true} if the first result is a ResultSet
   * @throws SQLException if execution fails
   */
  boolean execute(final String sql, final List<Object> parameters) throws SQLException {
    ensureOpen();
    String resolved = resolveSql(sql);
    if (FLUSH_CACHE_PATTERN.matcher(resolved).matches()) {
      connection.client().flushCache();
      lastExecuteWasQuery = false;
      lastUpdateCount = 0;
      return false;
    }
    if (DDL_PATTERN.matcher(resolved).lookingAt()) {
      doExecuteDdl(resolved, parameters);
      lastExecuteWasQuery = false;
      return false;
    }
    if (DML_PATTERN.matcher(resolved).lookingAt()) {
      doExecuteUpdate(resolved, parameters);
      lastExecuteWasQuery = false;
      return false;
    }
    doExecuteQuery(resolved, parameters);
    lastExecuteWasQuery = true;
    lastUpdateCount = -1;
    return true;
  }

  @Override
  public boolean execute(final String sql, final int autoGeneratedKeys) throws SQLException {
    return execute(sql);
  }

  @Override
  public boolean execute(final String sql, final int[] columnIndexes) throws SQLException {
    return execute(sql);
  }

  @Override
  public boolean execute(final String sql, final String[] columnNames) throws SQLException {
    return execute(sql);
  }

  @Override
  public int executeUpdate(final String sql) throws SQLException {
    ensureOpen();
    String resolved = resolveSql(sql);
    if (DDL_PATTERN.matcher(resolved).lookingAt()) {
      return doExecuteDdl(resolved);
    }
    return doExecuteUpdate(resolved);
  }

  @Override
  public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
    return executeUpdate(sql);
  }

  @Override
  public long executeLargeUpdate(final String sql) throws SQLException {
    ensureOpen();
    String resolved = resolveSql(sql);
    if (DDL_PATTERN.matcher(resolved).lookingAt()) {
      return doExecuteDdl(resolved);
    }
    return doExecuteUpdate(resolved);
  }

  @Override
  public long executeLargeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
    return executeLargeUpdate(sql);
  }

  @Override
  public long executeLargeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
    return executeLargeUpdate(sql);
  }

  @Override
  public long executeLargeUpdate(final String sql, final String[] columnNames) throws SQLException {
    return executeLargeUpdate(sql);
  }

  // ------------------------------------------------------------------
  // java.sql.Statement — batch operations (unsupported)
  // ------------------------------------------------------------------

  @Override
  public void addBatch(final String sql) throws SQLException {
    ensureOpen();
    throw unsupported("addBatch");
  }

  @Override
  public int[] executeBatch() throws SQLException {
    ensureOpen();
    throw unsupported("executeBatch");
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    ensureOpen();
    throw unsupported("executeLargeBatch");
  }

  @Override
  public void clearBatch() throws SQLException {
    // no-op
  }

  // ------------------------------------------------------------------
  // java.sql.Statement — result access
  // ------------------------------------------------------------------

  @Override
  public ResultSet getResultSet() throws SQLException {
    ensureOpen();
    return lastExecuteWasQuery ? currentResultSet : null;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    ensureOpen();
    return lastUpdateCount;
  }

  @Override
  public long getLargeUpdateCount() throws SQLException {
    ensureOpen();
    return lastUpdateCount;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(CLOSE_CURRENT_RESULT);
  }

  @Override
  public boolean getMoreResults(final int current) throws SQLException {
    ensureOpen();
    if (current == CLOSE_CURRENT_RESULT || current == CLOSE_ALL_RESULTS) {
      closeCurrentResultSet();
    }
    // Signal "no more results" per JDBC spec: getMoreResults() == false && getUpdateCount() == -1
    lastUpdateCount = -1;
    lastExecuteWasQuery = false;
    return false;
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    ensureOpen();
    return ResultSetBuilder.emptyResultSet();
  }

  // ------------------------------------------------------------------
  // java.sql.Statement — result set properties
  // ------------------------------------------------------------------

  @Override
  public int getResultSetType() throws SQLException {
    return resultSetType;
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return resultSetConcurrency;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return resultSetHoldability;
  }

  @Override
  public void setFetchDirection(final int direction) throws SQLException {
    // no-op
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(final int rows) throws SQLException {
    this.fetchSize = rows;
  }

  @Override
  public int getFetchSize() throws SQLException {
    return fetchSize;
  }

  // ------------------------------------------------------------------
  // java.sql.Statement — row / timeout / field limits
  // ------------------------------------------------------------------

  @Override
  public void setMaxRows(final int max) throws SQLException {
    this.maxRows = max;
  }

  @Override
  public int getMaxRows() throws SQLException {
    return maxRows;
  }

  @Override
  public void setLargeMaxRows(final long max) throws SQLException {
    this.maxRows = (int) max;
  }

  @Override
  public long getLargeMaxRows() throws SQLException {
    return (long) maxRows;
  }

  @Override
  public void setQueryTimeout(final int seconds) throws SQLException {
    this.queryTimeout = seconds;
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    return queryTimeout;
  }

  @Override
  public void setMaxFieldSize(final int max) throws SQLException {
    // no-op
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return 0;
  }

  // ------------------------------------------------------------------
  // java.sql.Statement — lifecycle
  // ------------------------------------------------------------------

  @Override
  public void close() throws SQLException {
    closeCurrentResultSet();
    closed = true;
  }

  @Override
  public boolean isClosed() throws SQLException {
    return closed;
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    this.closeOnCompletion = true;
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return closeOnCompletion;
  }

  // ------------------------------------------------------------------
  // java.sql.Statement — connection / warnings / misc
  // ------------------------------------------------------------------

  @Override
  public Connection getConnection() throws SQLException {
    return connection;
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    // no-op
  }

  @Override
  public void cancel() throws SQLException {
    // no-op
  }

  @Override
  public void setCursorName(final String name) throws SQLException {
    // no-op
  }

  @Override
  public void setEscapeProcessing(final boolean enable) throws SQLException {
    // no-op
  }

  @Override
  public void setPoolable(final boolean poolable) throws SQLException {
    // no-op
  }

  @Override
  public boolean isPoolable() throws SQLException {
    return false;
  }

  // ------------------------------------------------------------------
  // java.sql.Wrapper
  // ------------------------------------------------------------------

  @Override
  public <T> T unwrap(final Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("不支持 unwrap 到 " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(final Class<?> iface) throws SQLException {
    return iface.isInstance(this);
  }

  // ------------------------------------------------------------------
  // Internal utilities
  // ------------------------------------------------------------------

  static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  static SQLFeatureNotSupportedException unsupported(final String feature) {
    return new SQLFeatureNotSupportedException("DNA JDBC 驱动暂不支持: " + feature);
  }
}
