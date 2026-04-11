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
import java.util.Map;

/**
 * Concrete {@link PreparedStatement} implementation for the DNA JDBC driver.
 *
 * <p>Extends {@link DnaJdbcStatement} with parameter-binding logic.  Parameters
 * are collected via the various {@code set*} methods and rendered inline into
 * the SQL template before execution, because the DNA gateway does not support
 * server-side prepared statements.
 *
 * <p>Only read queries are supported; update and batch operations throw
 * {@link SQLFeatureNotSupportedException}.
 */
final class DnaJdbcPreparedStatement extends DnaJdbcStatement implements PreparedStatement {

  private final String sqlTemplate;

  private final Map<Integer, Object> parameters = new LinkedHashMap<>();

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
    return doExecuteQuery(renderPreparedSql());
  }

  @Override
  public boolean execute() throws SQLException {
    ensureOpen();
    doExecuteQuery(renderPreparedSql());
    return true;
  }

  @Override
  public int executeUpdate() throws SQLException {
    ensureOpen();
    throw unsupported("更新语句");
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    ensureOpen();
    throw unsupported("更新语句");
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
  // PreparedStatement — batch (unsupported)
  // ------------------------------------------------------------------

  @Override
  public void addBatch() throws SQLException {
    ensureOpen();
    throw unsupported("addBatch");
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
   * Renders the SQL template by replacing each {@code ?} placeholder with the
   * corresponding parameter literal.  Quoted regions (single- and
   * double-quoted) are left untouched so that question marks inside string
   * literals are not treated as bind markers.
   *
   * @return the rendered SQL string with all parameters inlined
   * @throws SQLException if a required parameter has not been set
   */
  private String renderPreparedSql() throws SQLException {
    StringBuilder builder = new StringBuilder(sqlTemplate.length() + 32);
    boolean singleQuoted = false;
    boolean doubleQuoted = false;
    int parameterIndex = 1;
    for (int index = 0; index < sqlTemplate.length(); index++) {
      char current = sqlTemplate.charAt(index);
      if (current == '\'' && !doubleQuoted) {
        builder.append(current);
        if (singleQuoted && index + 1 < sqlTemplate.length()
            && sqlTemplate.charAt(index + 1) == '\'') {
          builder.append(sqlTemplate.charAt(++index));
        } else {
          singleQuoted = !singleQuoted;
        }
        continue;
      }
      if (current == '"' && !singleQuoted) {
        doubleQuoted = !doubleQuoted;
        builder.append(current);
        continue;
      }
      if (current == '?' && !singleQuoted && !doubleQuoted) {
        if (!parameters.containsKey(parameterIndex)) {
          throw new SQLException("缺少 PreparedStatement 参数: " + parameterIndex);
        }
        builder.append(toSqlLiteral(parameters.get(parameterIndex)));
        parameterIndex++;
        continue;
      }
      builder.append(current);
    }
    return builder.toString();
  }

  /**
   * Converts a parameter value to its SQL literal representation.
   *
   * @param value the parameter value (may be {@code null})
   * @return the SQL literal string
   * @throws SQLException if the value type is not supported
   */
  private static String toSqlLiteral(final Object value) throws SQLException {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof byte[] bytes) {
      StringBuilder builder = new StringBuilder("X'");
      for (byte current : bytes) {
        builder.append(String.format("%02X", current));
      }
      builder.append('\'');
      return builder.toString();
    }
    if (value instanceof java.util.UUID) {
      return '\'' + value.toString() + '\'';
    }
    if (value instanceof java.time.LocalDate
        || value instanceof java.time.LocalTime
        || value instanceof java.time.LocalDateTime
        || value instanceof java.time.OffsetDateTime
        || value instanceof java.time.ZonedDateTime
        || value instanceof java.time.Instant) {
      return '\'' + value.toString() + '\'';
    }
    if (value instanceof java.sql.Date
        || value instanceof java.sql.Time
        || value instanceof java.sql.Timestamp
        || value instanceof java.time.temporal.TemporalAccessor
        || value instanceof java.util.Date
        || value instanceof BigDecimal
        || value instanceof Character
        || value instanceof String) {
      return '\'' + escapeSqlString(value.toString()) + '\'';
    }
    throw new SQLException("DNA JDBC PreparedStatement 暂不支持的参数类型: " + value.getClass().getName());
  }

  private static String escapeSqlString(final String raw) {
    StringBuilder builder = new StringBuilder(raw.length() + 8);
    for (int i = 0; i < raw.length(); i++) {
      char current = raw.charAt(i);
      switch (current) {
        case '\'' -> builder.append("''");
        case '\\' -> builder.append("\\\\");
        case '\0' -> builder.append("\\0");
        default -> builder.append(current);
      }
    }
    return builder.toString();
  }
}
