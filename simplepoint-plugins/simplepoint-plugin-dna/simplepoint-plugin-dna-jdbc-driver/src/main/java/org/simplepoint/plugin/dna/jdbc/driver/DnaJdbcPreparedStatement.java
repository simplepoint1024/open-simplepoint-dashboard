package org.simplepoint.plugin.dna.jdbc.driver;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete {@link PreparedStatement} implementation for the DNA JDBC driver.
 *
 * <p>Extends {@link DnaJdbcStatement} with parameter-binding logic.  Parameters
 * are collected via the various {@code set*} methods and sent alongside the SQL
 * template to the DNA gateway for server-side binding, preventing SQL injection.
 *
 * <p>Supports read queries (SELECT), DML (INSERT, UPDATE, DELETE, MERGE/UPSERT),
 * and DDL (CREATE, ALTER, DROP, TRUNCATE) statements.
 */
final class DnaJdbcPreparedStatement extends DnaJdbcStatement implements PreparedStatement {

  private final String sqlTemplate;

  private final Map<Integer, Object> parameters = new LinkedHashMap<>();

  private final java.util.List<java.util.List<Object>> batchParameterSets = new java.util.ArrayList<>();

  DnaJdbcPreparedStatement(
      final DnaJdbcConnection connection,
      final String sqlTemplate,
      final int resultSetType,
      final int resultSetConcurrency,
      final int resultSetHoldability
  ) {
    super(connection, resultSetType, resultSetConcurrency, resultSetHoldability);
    this.sqlTemplate = sqlTemplate;
  }

  // ------------------------------------------------------------------
  // PreparedStatement — query execution
  // ------------------------------------------------------------------

  @Override
  public ResultSet executeQuery() throws SQLException {
    ensureOpen();
    return doExecuteQuery(sqlTemplate, collectParameters());
  }

  @Override
  public boolean execute() throws SQLException {
    ensureOpen();
    List<Object> params = collectParameters();
    return super.execute(sqlTemplate, params);
  }

  @Override
  public int executeUpdate() throws SQLException {
    ensureOpen();
    List<Object> params = collectParameters();
    if (DDL_PATTERN.matcher(sqlTemplate).lookingAt()) {
      return doExecuteDdl(sqlTemplate, params);
    }
    return doExecuteUpdate(sqlTemplate, params);
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    ensureOpen();
    List<Object> params = collectParameters();
    if (DDL_PATTERN.matcher(sqlTemplate).lookingAt()) {
      return doExecuteDdl(sqlTemplate, params);
    }
    return doExecuteUpdate(sqlTemplate, params);
  }

  // ------------------------------------------------------------------
  // PreparedStatement — parameter binding
  // ------------------------------------------------------------------

  @Override
  public void clearParameters() throws SQLException {
    parameters.clear();
  }

  @Override
  public void setNull(final int parameterIndex, final int sqlType) throws SQLException {
    parameters.put(parameterIndex, null);
  }

  @Override
  public void setNull(final int parameterIndex, final int sqlType, final String typeName) throws SQLException {
    parameters.put(parameterIndex, null);
  }

  @Override
  public void setBoolean(final int parameterIndex, final boolean x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setByte(final int parameterIndex, final byte x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setShort(final int parameterIndex, final short x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setInt(final int parameterIndex, final int x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setLong(final int parameterIndex, final long x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setFloat(final int parameterIndex, final float x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setDouble(final int parameterIndex, final double x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setBigDecimal(final int parameterIndex, final BigDecimal x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setString(final int parameterIndex, final String x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setBytes(final int parameterIndex, final byte[] x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setDate(final int parameterIndex, final Date x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setDate(final int parameterIndex, final Date x, final Calendar cal) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setTime(final int parameterIndex, final Time x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setTime(final int parameterIndex, final Time x, final Calendar cal) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setTimestamp(final int parameterIndex, final Timestamp x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setTimestamp(final int parameterIndex, final Timestamp x, final Calendar cal) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setObject(final int parameterIndex, final Object x) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setObject(final int parameterIndex, final Object x, final int targetSqlType) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  @Override
  public void setObject(
      final int parameterIndex, final Object x, final int targetSqlType, final int scaleOrLength
  ) throws SQLException {
    parameters.put(parameterIndex, x);
  }

  // ------------------------------------------------------------------
  // PreparedStatement — metadata
  // ------------------------------------------------------------------

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return currentResultSet != null ? currentResultSet.getMetaData() : null;
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    throw unsupported("getParameterMetaData");
  }

  // ------------------------------------------------------------------
  // PreparedStatement — batch
  // ------------------------------------------------------------------

  @Override
  public void addBatch() throws SQLException {
    ensureOpen();
    batchParameterSets.add(collectParameters());
    parameters.clear();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    ensureOpen();
    if (batchParameterSets.isEmpty()) {
      return new int[0];
    }
    try {
      java.util.List<DnaJdbcModels.SocketRequest> requests = new java.util.ArrayList<>(batchParameterSets.size());
      for (java.util.List<Object> params : batchParameterSets) {
        requests.add(DnaJdbcModels.SocketRequest.builder("EXECUTE_UPDATE")
            .catalogCode(connection.currentCatalog())
            .sql(sqlTemplate)
            .defaultSchema(connection.currentSchema())
            .parameters(params)
            .build());
      }
      java.util.List<DnaJdbcModels.SocketResponse> responses = connection.client().batch(requests);
      int[] counts = new int[batchParameterSets.size()];
      for (int i = 0; i < counts.length; i++) {
        if (i < responses.size() && Boolean.TRUE.equals(responses.get(i).success())) {
          DnaJdbcModels.UpdateResult ur = responses.get(i).updateResult();
          counts[i] = (int) (ur != null && ur.affectedRows() != null ? ur.affectedRows() : SUCCESS_NO_INFO);
        } else {
          counts[i] = EXECUTE_FAILED;
        }
      }
      return counts;
    } finally {
      batchParameterSets.clear();
    }
  }

  @Override
  public void clearBatch() throws SQLException {
    batchParameterSets.clear();
    parameters.clear();
  }

  // ------------------------------------------------------------------
  // PreparedStatement — stream / LOB / advanced setters (unsupported)
  // ------------------------------------------------------------------

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x, final int length) throws SQLException {
    throw unsupported("setAsciiStream");
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x, final long length) throws SQLException {
    throw unsupported("setAsciiStream");
  }

  @Override
  public void setAsciiStream(final int parameterIndex, final InputStream x) throws SQLException {
    throw unsupported("setAsciiStream");
  }

  @SuppressWarnings("deprecation")
  @Override
  public void setUnicodeStream(final int parameterIndex, final InputStream x, final int length) throws SQLException {
    throw unsupported("setUnicodeStream");
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x, final int length) throws SQLException {
    throw unsupported("setBinaryStream");
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x, final long length) throws SQLException {
    throw unsupported("setBinaryStream");
  }

  @Override
  public void setBinaryStream(final int parameterIndex, final InputStream x) throws SQLException {
    throw unsupported("setBinaryStream");
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader, final int length) throws SQLException {
    throw unsupported("setCharacterStream");
  }

  @Override
  public void setCharacterStream(
      final int parameterIndex, final Reader reader, final long length
  ) throws SQLException {
    throw unsupported("setCharacterStream");
  }

  @Override
  public void setCharacterStream(final int parameterIndex, final Reader reader) throws SQLException {
    throw unsupported("setCharacterStream");
  }

  @Override
  public void setNCharacterStream(final int parameterIndex, final Reader value, final long length)
      throws SQLException {
    throw unsupported("setNCharacterStream");
  }

  @Override
  public void setNCharacterStream(final int parameterIndex, final Reader value) throws SQLException {
    throw unsupported("setNCharacterStream");
  }

  @Override
  public void setRef(final int parameterIndex, final Ref x) throws SQLException {
    throw unsupported("setRef");
  }

  @Override
  public void setBlob(final int parameterIndex, final Blob x) throws SQLException {
    throw unsupported("setBlob");
  }

  @Override
  public void setBlob(final int parameterIndex, final InputStream inputStream, final long length)
      throws SQLException {
    throw unsupported("setBlob");
  }

  @Override
  public void setBlob(final int parameterIndex, final InputStream inputStream) throws SQLException {
    throw unsupported("setBlob");
  }

  @Override
  public void setClob(final int parameterIndex, final Clob x) throws SQLException {
    throw unsupported("setClob");
  }

  @Override
  public void setClob(final int parameterIndex, final Reader reader, final long length) throws SQLException {
    throw unsupported("setClob");
  }

  @Override
  public void setClob(final int parameterIndex, final Reader reader) throws SQLException {
    throw unsupported("setClob");
  }

  @Override
  public void setNClob(final int parameterIndex, final NClob value) throws SQLException {
    throw unsupported("setNClob");
  }

  @Override
  public void setNClob(final int parameterIndex, final Reader reader, final long length) throws SQLException {
    throw unsupported("setNClob");
  }

  @Override
  public void setNClob(final int parameterIndex, final Reader reader) throws SQLException {
    throw unsupported("setNClob");
  }

  @Override
  public void setArray(final int parameterIndex, final Array x) throws SQLException {
    throw unsupported("setArray");
  }

  @Override
  public void setURL(final int parameterIndex, final URL x) throws SQLException {
    throw unsupported("setURL");
  }

  @Override
  public void setRowId(final int parameterIndex, final RowId x) throws SQLException {
    throw unsupported("setRowId");
  }

  @Override
  public void setNString(final int parameterIndex, final String value) throws SQLException {
    parameters.put(parameterIndex, value);
  }

  @Override
  public void setSQLXML(final int parameterIndex, final SQLXML xmlObject) throws SQLException {
    throw unsupported("setSQLXML");
  }

  // ------------------------------------------------------------------
  // SQL rendering
  // ------------------------------------------------------------------

  /**
   * Collects parameters into an ordered list, verifying all positional
   * placeholders have been set.
   *
   * @return ordered list of parameter values
   * @throws SQLException if a required parameter has not been set
   */
  private List<Object> collectParameters() throws SQLException {
    if (parameters.isEmpty()) {
      return List.of();
    }
    int maxIndex = parameters.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    List<Object> result = new java.util.ArrayList<>(maxIndex);
    for (int i = 1; i <= maxIndex; i++) {
      if (!parameters.containsKey(i)) {
        throw new SQLException("缺少 PreparedStatement 参数: " + i);
      }
      result.add(parameters.get(i));
    }
    return result;
  }
}
