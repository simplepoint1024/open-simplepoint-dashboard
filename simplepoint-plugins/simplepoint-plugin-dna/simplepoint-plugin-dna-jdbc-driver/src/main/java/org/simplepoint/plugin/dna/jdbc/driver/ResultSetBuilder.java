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
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

/**
 * Builds cached JDBC result sets from DNA gateway responses.
 */
final class ResultSetBuilder {

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

  private static int resolveJdbcType(final DnaJdbcModels.ColumnDef column) {
    if (column == null) {
      return Types.VARCHAR;
    }
    if (column.jdbcType() != null) {
      return column.jdbcType();
    }
    String typeName = column.typeName() == null ? "" : column.typeName().toUpperCase();
    return switch (typeName) {
      case "BOOLEAN", "BIT" -> Types.BOOLEAN;
      case "TINYINT" -> Types.TINYINT;
      case "SMALLINT", "INT2" -> Types.SMALLINT;
      case "INTEGER", "INT", "INT4", "MEDIUMINT" -> Types.INTEGER;
      case "BIGINT", "INT8", "SERIAL", "BIGSERIAL" -> Types.BIGINT;
      case "REAL", "FLOAT4" -> Types.REAL;
      case "FLOAT", "FLOAT8" -> Types.FLOAT;
      case "DOUBLE", "DOUBLE PRECISION" -> Types.DOUBLE;
      case "DECIMAL", "NUMERIC", "NUMBER", "MONEY", "SMALLMONEY" -> Types.DECIMAL;
      case "CHAR", "BPCHAR", "CHARACTER" -> Types.CHAR;
      case "NCHAR", "NATIONAL CHAR", "NATIONAL CHARACTER" -> Types.NCHAR;
      case "NVARCHAR", "NATIONAL VARCHAR", "NVARCHAR2", "NATIONAL CHARACTER VARYING" -> Types.NVARCHAR;
      case "LONGVARCHAR", "MEDIUMTEXT", "LONGTEXT", "TEXT" -> Types.LONGVARCHAR;
      case "LONGNVARCHAR", "NTEXT" -> Types.LONGNVARCHAR;
      case "CLOB" -> Types.CLOB;
      case "NCLOB" -> Types.NCLOB;
      case "BLOB", "BYTEA", "IMAGE" -> Types.BLOB;
      case "BINARY" -> Types.BINARY;
      case "VARBINARY", "RAW", "LONG RAW" -> Types.VARBINARY;
      case "LONGVARBINARY" -> Types.LONGVARBINARY;
      case "DATE" -> Types.DATE;
      case "TIME", "TIME_WITH_TIMEZONE" -> Types.TIME;
      case "TIMESTAMP", "TIMESTAMP_WITH_TIMEZONE", "DATETIME", "DATETIME2", "SMALLDATETIME",
          "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITHOUT TIME ZONE",
          "TIMESTAMPTZ" -> Types.TIMESTAMP;
      case "ARRAY" -> Types.ARRAY;
      case "STRUCT" -> Types.STRUCT;
      case "REF" -> Types.REF;
      case "SQLXML", "XML" -> Types.SQLXML;
      case "ROWID" -> Types.ROWID;
      case "OTHER", "JSON", "JSONB", "UUID", "GEOMETRY", "GEOGRAPHY", "POINT",
          "INTERVAL", "INET", "CIDR", "MACADDR", "ENUM", "SET",
          "HSTORE", "TSVECTOR", "TSQUERY" -> Types.OTHER;
      default -> Types.VARCHAR;
    };
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
