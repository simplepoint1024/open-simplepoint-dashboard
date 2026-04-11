package org.simplepoint.plugin.dna.jdbc.driver;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

/**
 * Builds cached JDBC result sets from DNA gateway responses.
 * <p>
 * The server side now performs authoritative type mapping via dialect-specific
 * {@code JdbcTypeMapping} instances. The client side trusts the server-provided
 * {@code jdbcType} and only applies a lightweight fallback when the server sends
 * {@link Types#OTHER} or zero.
 */
final class ResultSetBuilder {

  /**
   * Lightweight client-side type name → JDBC type fallback mapping.
   * Used only when the server-provided jdbcType is missing or OTHER.
   */
  private static final Map<String, Integer> FALLBACK_TYPE_MAP = Map.ofEntries(
      Map.entry("BOOLEAN", Types.BOOLEAN),
      Map.entry("BIT", Types.BIT),
      Map.entry("TINYINT", Types.TINYINT),
      Map.entry("SMALLINT", Types.SMALLINT),
      Map.entry("INT2", Types.SMALLINT),
      Map.entry("INTEGER", Types.INTEGER),
      Map.entry("INT", Types.INTEGER),
      Map.entry("INT4", Types.INTEGER),
      Map.entry("MEDIUMINT", Types.INTEGER),
      Map.entry("BIGINT", Types.BIGINT),
      Map.entry("INT8", Types.BIGINT),
      Map.entry("SERIAL", Types.BIGINT),
      Map.entry("BIGSERIAL", Types.BIGINT),
      Map.entry("REAL", Types.REAL),
      Map.entry("FLOAT4", Types.REAL),
      Map.entry("FLOAT", Types.FLOAT),
      Map.entry("FLOAT8", Types.FLOAT),
      Map.entry("DOUBLE", Types.DOUBLE),
      Map.entry("DOUBLE PRECISION", Types.DOUBLE),
      Map.entry("DECIMAL", Types.DECIMAL),
      Map.entry("NUMERIC", Types.NUMERIC),
      Map.entry("NUMBER", Types.NUMERIC),
      Map.entry("MONEY", Types.DECIMAL),
      Map.entry("SMALLMONEY", Types.DECIMAL),
      Map.entry("CHAR", Types.CHAR),
      Map.entry("BPCHAR", Types.CHAR),
      Map.entry("CHARACTER", Types.CHAR),
      Map.entry("VARCHAR", Types.VARCHAR),
      Map.entry("VARCHAR2", Types.VARCHAR),
      Map.entry("CHARACTER VARYING", Types.VARCHAR),
      Map.entry("NCHAR", Types.NCHAR),
      Map.entry("NVARCHAR", Types.NVARCHAR),
      Map.entry("NVARCHAR2", Types.NVARCHAR),
      Map.entry("TEXT", Types.LONGVARCHAR),
      Map.entry("MEDIUMTEXT", Types.LONGVARCHAR),
      Map.entry("LONGTEXT", Types.LONGVARCHAR),
      Map.entry("NTEXT", Types.LONGNVARCHAR),
      Map.entry("CLOB", Types.CLOB),
      Map.entry("NCLOB", Types.NCLOB),
      Map.entry("BLOB", Types.BLOB),
      Map.entry("BYTEA", Types.BLOB),
      Map.entry("IMAGE", Types.BLOB),
      Map.entry("BINARY", Types.BINARY),
      Map.entry("VARBINARY", Types.VARBINARY),
      Map.entry("RAW", Types.VARBINARY),
      Map.entry("LONG RAW", Types.VARBINARY),
      Map.entry("LONGVARBINARY", Types.LONGVARBINARY),
      Map.entry("DATE", Types.DATE),
      Map.entry("TIME", Types.TIME),
      Map.entry("TIME WITH TIME ZONE", Types.TIME_WITH_TIMEZONE),
      Map.entry("TIMETZ", Types.TIME_WITH_TIMEZONE),
      Map.entry("TIMESTAMP", Types.TIMESTAMP),
      Map.entry("DATETIME", Types.TIMESTAMP),
      Map.entry("DATETIME2", Types.TIMESTAMP),
      Map.entry("SMALLDATETIME", Types.TIMESTAMP),
      Map.entry("TIMESTAMP WITH TIME ZONE", Types.TIMESTAMP_WITH_TIMEZONE),
      Map.entry("TIMESTAMPTZ", Types.TIMESTAMP_WITH_TIMEZONE),
      Map.entry("TIMESTAMP WITHOUT TIME ZONE", Types.TIMESTAMP),
      Map.entry("ARRAY", Types.ARRAY),
      Map.entry("STRUCT", Types.STRUCT),
      Map.entry("REF", Types.REF),
      Map.entry("XML", Types.SQLXML),
      Map.entry("SQLXML", Types.SQLXML),
      Map.entry("ROWID", Types.ROWID)
  );

  private ResultSetBuilder() {
  }

  static ResultSet fromTabularResult(final DnaJdbcModels.TabularResult result) throws SQLException {
    List<DnaJdbcModels.ColumnDef> columns = result == null ? List.of() : safeColumns(result.columns());
    List<List<Object>> rows = result == null || result.rows() == null ? List.of() : result.rows();
    return build(columns, rows, 0);
  }

  static ResultSet fromQueryResult(final DnaJdbcModels.QueryResult result, final int maxRows) throws SQLException {
    List<DnaJdbcModels.ColumnDef> columns = result == null ? List.of() : safeColumns(result.columns());
    List<List<Object>> rows = result == null || result.rows() == null ? List.of() : result.rows();
    return build(columns, rows, maxRows);
  }

  static ResultSet emptyResultSet() throws SQLException {
    return build(List.of(new DnaJdbcModels.ColumnDef("VALUE", "VARCHAR", Types.VARCHAR)), List.of(), 0);
  }

  private static ResultSet build(
      final List<DnaJdbcModels.ColumnDef> columns,
      final List<List<Object>> rows,
      final int maxRows
  ) throws SQLException {
    CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
    RowSetMetaDataImpl metaData = new RowSetMetaDataImpl();
    metaData.setColumnCount(columns.size());
    for (int index = 0; index < columns.size(); index++) {
      DnaJdbcModels.ColumnDef column = columns.get(index);
      int columnIndex = index + 1;
      int jdbcType = resolveJdbcType(column);
      metaData.setColumnLabel(columnIndex, column.name());
      metaData.setColumnName(columnIndex, column.name());
      metaData.setColumnType(columnIndex, jdbcType);
      metaData.setColumnTypeName(columnIndex, column.typeName() == null ? "VARCHAR" : column.typeName());
      metaData.setNullable(columnIndex, ResultSetMetaDataConstant.NULLABLE);
      metaData.setAutoIncrement(columnIndex, false);
      metaData.setCaseSensitive(columnIndex, isTypeCaseSensitive(jdbcType));
      metaData.setCurrency(columnIndex, false);
      metaData.setSearchable(columnIndex, true);
      metaData.setSigned(columnIndex, isTypeSigned(jdbcType));
    }
    rowSet.setMetaData(metaData);
    int limit = maxRows > 0 ? Math.min(maxRows, rows.size()) : rows.size();
    for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
      List<Object> row = rows.get(rowIndex) == null ? List.of() : rows.get(rowIndex);
      rowSet.moveToInsertRow();
      for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
        rowSet.updateObject(columnIndex + 1, columnIndex < row.size() ? row.get(columnIndex) : null);
      }
      rowSet.insertRow();
    }
    rowSet.moveToCurrentRow();
    rowSet.beforeFirst();
    return wrap(rowSet);
  }

  private static ResultSet wrap(final CachedRowSet rowSet) {
    return (ResultSet) Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(),
        new Class<?>[] {ResultSet.class},
        new ResultSetHandler(rowSet)
    );
  }

  /**
   * Resolves the JDBC type for a column. Trusts the server-provided jdbcType first;
   * falls back to the lightweight client-side type name map when the server sends 0 or OTHER.
   */
  private static int resolveJdbcType(final DnaJdbcModels.ColumnDef column) {
    if (column == null) {
      return Types.VARCHAR;
    }
    int serverType = column.jdbcType() != null ? column.jdbcType() : 0;
    if (serverType != 0 && serverType != Types.OTHER) {
      return serverType;
    }
    String typeName = column.typeName();
    if (typeName == null || typeName.isBlank()) {
      return serverType != 0 ? serverType : Types.VARCHAR;
    }
    String upper = typeName.toUpperCase(Locale.ROOT).trim();
    // Strip size qualifiers: VARCHAR(255) → VARCHAR
    int paren = upper.indexOf('(');
    if (paren > 0) {
      upper = upper.substring(0, paren).trim();
    }
    Integer mapped = FALLBACK_TYPE_MAP.get(upper);
    if (mapped != null) {
      return mapped;
    }
    // If server said OTHER, keep it; otherwise default to VARCHAR
    return serverType != 0 ? serverType : Types.VARCHAR;
  }

  private static List<DnaJdbcModels.ColumnDef> safeColumns(final List<DnaJdbcModels.ColumnDef> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    List<DnaJdbcModels.ColumnDef> normalized = new ArrayList<>(values.size());
    for (DnaJdbcModels.ColumnDef value : values) {
      if (value == null) {
        normalized.add(new DnaJdbcModels.ColumnDef("COLUMN", "VARCHAR", Types.VARCHAR));
      } else {
        normalized.add(value);
      }
    }
    return List.copyOf(normalized);
  }

  private static final class ResultSetMetaDataConstant {

    private static final int NULLABLE_UNKNOWN = 2;

    private static final int NULLABLE = 1;

    private ResultSetMetaDataConstant() {
    }
  }

  private static boolean isTypeSigned(final int jdbcType) {
    return switch (jdbcType) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
           Types.REAL, Types.FLOAT, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> true;
      default -> false;
    };
  }

  private static boolean isTypeCaseSensitive(final int jdbcType) {
    return switch (jdbcType) {
      case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
           Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
           Types.CLOB, Types.NCLOB -> true;
      default -> false;
    };
  }

  private static final class ResultSetHandler implements InvocationHandler {

    private final CachedRowSet delegate;

    private ResultSetHandler(final CachedRowSet delegate) {
      this.delegate = delegate;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
      Object[] safeArgs = args == null ? new Object[0] : args;
      try {
        return switch (method.getName()) {
          case "toString" -> delegate.toString();
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == safeArgs[0];
          case "unwrap" -> unwrap(proxy, safeArgs);
          case "isWrapperFor" -> isWrapperFor(proxy, safeArgs);
          case "absolute" -> absolute(safeArgs);
          default -> method.invoke(delegate, safeArgs);
        };
      } catch (InvocationTargetException exception) {
        throw exception.getCause();
      }
    }

    private Object absolute(final Object[] args) throws SQLException {
      int row = ((Number) args[0]).intValue();
      if (row == 0) {
        delegate.beforeFirst();
        return false;
      }
      if (row < 0) {
        return absoluteFromEnd(row);
      }
      return delegate.absolute(row);
    }

    private boolean absoluteFromEnd(final int row) throws SQLException {
      delegate.last();
      int rowCount = delegate.getRow();
      int targetRow = rowCount + row + 1;
      if (targetRow <= 0) {
        delegate.beforeFirst();
        return false;
      }
      return delegate.absolute(targetRow);
    }

    private static Object unwrap(final Object proxy, final Object[] args) throws SQLException {
      Class<?> targetType = (Class<?>) args[0];
      if (targetType.isInstance(proxy)) {
        return proxy;
      }
      throw new SQLException("不支持 unwrap 到 " + targetType.getName());
    }

    private static boolean isWrapperFor(final Object proxy, final Object[] args) {
      return ((Class<?>) args[0]).isInstance(proxy);
    }
  }
}
