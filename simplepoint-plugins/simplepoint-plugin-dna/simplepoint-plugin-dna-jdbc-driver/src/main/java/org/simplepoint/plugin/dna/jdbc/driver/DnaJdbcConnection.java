package org.simplepoint.plugin.dna.jdbc.driver;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Concrete {@link Connection} implementation for the DNA JDBC driver.
 *
 * <p>This is a read-only, auto-commit connection that delegates query execution
 * to a {@link DnaJdbcClient}. Transactions, stored procedures, LOB creation,
 * and other write-oriented features are not supported.
 */
final class DnaJdbcConnection implements Connection {

  private final String url;
  private final DnaJdbcClient client;
  private final String databaseProductName;
  private final String databaseProductVersion;
  private final String loginSubject;
  private final Properties clientInfo;
  private String currentCatalog;
  private String currentSchema;
  private boolean closed;
  private DatabaseMetaData databaseMetaData;

  private DnaJdbcConnection(
      final String url,
      final DnaJdbcClient client,
      final String databaseProductName,
      final String databaseProductVersion,
      final String loginSubject,
      final String currentCatalog,
      final String currentSchema
  ) {
    this.url = url;
    this.client = client;
    this.databaseProductName = databaseProductName;
    this.databaseProductVersion = databaseProductVersion;
    this.loginSubject = loginSubject;
    this.currentCatalog = currentCatalog;
    this.currentSchema = currentSchema;
    this.clientInfo = new Properties();
  }

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  static DnaJdbcConnection open(final DnaJdbcModels.ConnectionConfig config) throws SQLException {
    DnaJdbcClient client = new DnaJdbcClient(config);
    DnaJdbcModels.PingResult pingResult = client.ping();
    return new DnaJdbcConnection(
        config.originalUrl(),
        client,
        pingResult.databaseProductName(),
        pingResult.databaseProductVersion(),
        config.loginSubject(),
        trimToNull(config.catalogCode()) == null
            ? trimToNull(pingResult.catalogCode()) : trimToNull(config.catalogCode()),
        trimToNull(config.schema()) == null
            ? trimToNull(pingResult.currentSchema()) : trimToNull(config.schema())
    );
  }

  // ----------------------------------------------------------------
  // Package-private accessors for sibling classes
  // ----------------------------------------------------------------

  DnaJdbcClient client() {
    return client;
  }

  String currentCatalog() {
    return currentCatalog;
  }

  String currentSchema() {
    return currentSchema;
  }

  String url() {
    return url;
  }

  String databaseProductName() {
    return databaseProductName;
  }

  String databaseProductVersion() {
    return databaseProductVersion;
  }

  String loginSubject() {
    return loginSubject;
  }

  // ----------------------------------------------------------------
  // Statement creation
  // ----------------------------------------------------------------

  @Override
  public Statement createStatement() throws SQLException {
    ensureOpen();
    return new DnaJdbcStatement(
        this,
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT
    );
  }

  @Override
  public Statement createStatement(final int resultSetType, final int resultSetConcurrency) throws SQLException {
    ensureOpen();
    validateResultSetOptions(resultSetType, resultSetConcurrency);
    return new DnaJdbcStatement(
        this,
        resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT
    );
  }

  @Override
  public Statement createStatement(
      final int resultSetType,
      final int resultSetConcurrency,
      final int resultSetHoldability
  ) throws SQLException {
    ensureOpen();
    validateResultSetOptions(resultSetType, resultSetConcurrency);
    return new DnaJdbcStatement(
        this,
        resultSetType, resultSetConcurrency, resultSetHoldability
    );
  }

  @Override
  public PreparedStatement prepareStatement(final String sql) throws SQLException {
    ensureOpen();
    String normalizedSql = requireNonEmptySql(sql);
    // TODO: replace with new DnaJdbcPreparedStatement(...) once that class is created
    return DnaJdbcProxies.createPreparedStatementProxy(
        this, normalizedSql,
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT
    );
  }

  @Override
  public PreparedStatement prepareStatement(
      final String sql, final int resultSetType, final int resultSetConcurrency
  ) throws SQLException {
    ensureOpen();
    String normalizedSql = requireNonEmptySql(sql);
    validateResultSetOptions(resultSetType, resultSetConcurrency);
    // TODO: replace with new DnaJdbcPreparedStatement(...) once that class is created
    return DnaJdbcProxies.createPreparedStatementProxy(
        this, normalizedSql,
        resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT
    );
  }

  @Override
  public PreparedStatement prepareStatement(
      final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability
  ) throws SQLException {
    ensureOpen();
    String normalizedSql = requireNonEmptySql(sql);
    validateResultSetOptions(resultSetType, resultSetConcurrency);
    // TODO: replace with new DnaJdbcPreparedStatement(...) once that class is created
    return DnaJdbcProxies.createPreparedStatementProxy(
        this, normalizedSql,
        resultSetType, resultSetConcurrency, resultSetHoldability
    );
  }

  @Override
  public PreparedStatement prepareStatement(final String sql, final int autoGeneratedKeys) throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(final String sql, final int[] columnIndexes) throws SQLException {
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(final String sql, final String[] columnNames) throws SQLException {
    return prepareStatement(sql);
  }

  // ----------------------------------------------------------------
  // Metadata
  // ----------------------------------------------------------------

  @Override
  public DatabaseMetaData getMetaData() throws SQLException {
    ensureOpen();
    if (databaseMetaData == null) {
      databaseMetaData = new DnaJdbcDatabaseMetaData(this);
    }
    return databaseMetaData;
  }

  // ----------------------------------------------------------------
  // Lifecycle
  // ----------------------------------------------------------------

  @Override
  public void close() throws SQLException {
    client.close();
    closed = true;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public boolean isValid(final int timeout) {
    try {
      client.ping();
      return !closed;
    } catch (SQLException ex) {
      return false;
    }
  }

  @Override
  public void abort(final Executor executor) throws SQLException {
    client.close();
    closed = true;
  }

  // ----------------------------------------------------------------
  // Read-only / auto-commit enforcement
  // ----------------------------------------------------------------

  @Override
  public void setReadOnly(final boolean readOnly) throws SQLException {
    ensureOpen();
    if (!readOnly) {
      throw unsupported("写入事务");
    }
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    ensureOpen();
    return true;
  }

  @Override
  public void setAutoCommit(final boolean autoCommit) throws SQLException {
    ensureOpen();
    if (!autoCommit) {
      throw unsupported("事务控制");
    }
  }

  @Override
  public boolean getAutoCommit() throws SQLException {
    ensureOpen();
    return true;
  }

  // ----------------------------------------------------------------
  // Transaction isolation
  // ----------------------------------------------------------------

  @Override
  public int getTransactionIsolation() throws SQLException {
    ensureOpen();
    return Connection.TRANSACTION_NONE;
  }

  @Override
  public void setTransactionIsolation(final int level) throws SQLException {
    ensureOpen();
    if (level != Connection.TRANSACTION_NONE) {
      throw unsupported("事务隔离级别");
    }
  }

  // ----------------------------------------------------------------
  // Catalog / schema
  // ----------------------------------------------------------------

  @Override
  public String getCatalog() throws SQLException {
    ensureOpen();
    return currentCatalog;
  }

  @Override
  public void setCatalog(final String catalog) throws SQLException {
    ensureOpen();
    this.currentCatalog = trimToNull(catalog);
  }

  @Override
  public String getSchema() throws SQLException {
    ensureOpen();
    return currentSchema;
  }

  @Override
  public void setSchema(final String schema) throws SQLException {
    ensureOpen();
    this.currentSchema = trimToNull(schema);
  }

  // ----------------------------------------------------------------
  // SQL passthrough
  // ----------------------------------------------------------------

  @Override
  public String nativeSQL(final String sql) throws SQLException {
    ensureOpen();
    return sql;
  }

  // ----------------------------------------------------------------
  // Warnings
  // ----------------------------------------------------------------

  @Override
  public SQLWarning getWarnings() throws SQLException {
    ensureOpen();
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    ensureOpen();
  }

  // ----------------------------------------------------------------
  // Holdability
  // ----------------------------------------------------------------

  @Override
  public int getHoldability() throws SQLException {
    ensureOpen();
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public void setHoldability(final int holdability) throws SQLException {
    ensureOpen();
  }

  // ----------------------------------------------------------------
  // Client info
  // ----------------------------------------------------------------

  @Override
  public Properties getClientInfo() throws SQLException {
    ensureOpen();
    Properties copy = new Properties();
    copy.putAll(clientInfo);
    return copy;
  }

  @Override
  public String getClientInfo(final String name) throws SQLException {
    ensureOpen();
    return clientInfo.getProperty(name);
  }

  @Override
  public void setClientInfo(final Properties properties) throws SQLClientInfoException {
    clientInfo.clear();
    clientInfo.putAll(properties);
  }

  @Override
  public void setClientInfo(final String name, final String value) throws SQLClientInfoException {
    clientInfo.setProperty(name, Objects.toString(value, ""));
  }

  // ----------------------------------------------------------------
  // Type map
  // ----------------------------------------------------------------

  @Override
  public void setTypeMap(final Map<String, Class<?>> map) throws SQLException {
    ensureOpen();
  }

  @Override
  public Map<String, Class<?>> getTypeMap() throws SQLException {
    ensureOpen();
    return new LinkedHashMap<>();
  }

  // ----------------------------------------------------------------
  // Network timeout / request lifecycle
  // ----------------------------------------------------------------

  @Override
  public int getNetworkTimeout() throws SQLException {
    ensureOpen();
    return 0;
  }

  @Override
  public void setNetworkTimeout(final Executor executor, final int milliseconds) throws SQLException {
    ensureOpen();
  }

  // ----------------------------------------------------------------
  // Unsupported transactional operations
  // ----------------------------------------------------------------

  @Override
  public void commit() throws SQLException {
    ensureOpen();
    throw unsupported("commit");
  }

  @Override
  public void rollback() throws SQLException {
    ensureOpen();
    throw unsupported("rollback");
  }

  @Override
  public void rollback(final Savepoint savepoint) throws SQLException {
    ensureOpen();
    throw unsupported("rollback");
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    ensureOpen();
    throw unsupported("setSavepoint");
  }

  @Override
  public Savepoint setSavepoint(final String name) throws SQLException {
    ensureOpen();
    throw unsupported("setSavepoint");
  }

  @Override
  public void releaseSavepoint(final Savepoint savepoint) throws SQLException {
    ensureOpen();
    throw unsupported("releaseSavepoint");
  }

  // ----------------------------------------------------------------
  // Unsupported callable / LOB / structured types
  // ----------------------------------------------------------------

  @Override
  public CallableStatement prepareCall(final String sql) throws SQLException {
    ensureOpen();
    throw unsupported("prepareCall");
  }

  @Override
  public CallableStatement prepareCall(
      final String sql, final int resultSetType, final int resultSetConcurrency
  ) throws SQLException {
    ensureOpen();
    throw unsupported("prepareCall");
  }

  @Override
  public CallableStatement prepareCall(
      final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability
  ) throws SQLException {
    ensureOpen();
    throw unsupported("prepareCall");
  }

  @Override
  public Clob createClob() throws SQLException {
    ensureOpen();
    throw unsupported("createClob");
  }

  @Override
  public Blob createBlob() throws SQLException {
    ensureOpen();
    throw unsupported("createBlob");
  }

  @Override
  public NClob createNClob() throws SQLException {
    ensureOpen();
    throw unsupported("createNClob");
  }

  @Override
  public SQLXML createSQLXML() throws SQLException {
    ensureOpen();
    throw unsupported("createSQLXML");
  }

  @Override
  public Array createArrayOf(final String typeName, final Object[] elements) throws SQLException {
    ensureOpen();
    throw unsupported("createArrayOf");
  }

  @Override
  public Struct createStruct(final String typeName, final Object[] attributes) throws SQLException {
    ensureOpen();
    throw unsupported("createStruct");
  }

  // ----------------------------------------------------------------
  // Wrapper
  // ----------------------------------------------------------------

  @SuppressWarnings("unchecked")
  @Override
  public <T> T unwrap(final Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return (T) this;
    }
    throw new SQLException("不支持 unwrap 到 " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(final Class<?> iface) {
    return iface.isInstance(this);
  }

  // ----------------------------------------------------------------
  // Object overrides
  // ----------------------------------------------------------------

  @Override
  public String toString() {
    return "DnaJdbcConnection";
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public boolean equals(final Object obj) {
    return this == obj;
  }

  // ----------------------------------------------------------------
  // Private helpers
  // ----------------------------------------------------------------

  private void ensureOpen() throws SQLException {
    if (closed) {
      throw new SQLException("DNA JDBC 连接已关闭");
    }
  }

  private static void validateResultSetOptions(
      final int resultSetType, final int resultSetConcurrency
  ) throws SQLException {
    if (resultSetType != ResultSet.TYPE_FORWARD_ONLY && resultSetType != ResultSet.TYPE_SCROLL_INSENSITIVE) {
      throw unsupported("ResultSet 类型 " + resultSetType);
    }
    if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
      throw unsupported("ResultSet 并发模式 " + resultSetConcurrency);
    }
  }

  private static String requireNonEmptySql(final String sql) throws SQLException {
    String normalized = trimToNull(sql);
    if (normalized == null) {
      throw new SQLException("SQL 不能为空");
    }
    return normalized;
  }

  private static SQLFeatureNotSupportedException unsupported(final String feature) {
    return new SQLFeatureNotSupportedException("DNA JDBC 驱动暂不支持: " + feature);
  }

  static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
