package org.simplepoint.plugin.dna.jdbc.driver;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Dynamic proxies used by the standalone DNA JDBC driver.
 */
final class DnaJdbcProxies {

  private DnaJdbcProxies() {
  }

  static Connection openConnection(final DnaJdbcModels.ConnectionConfig config) throws SQLException {
    DnaJdbcClient client = new DnaJdbcClient(config);
    DnaJdbcModels.PingResult pingResult = client.ping();
    ConnectionState state = new ConnectionState(
        config.originalUrl(),
        client,
        pingResult.databaseProductName(),
        pingResult.databaseProductVersion(),
        config.loginSubject(),
        trimToNull(config.catalogCode()) == null ? trimToNull(pingResult.catalogCode()) : trimToNull(config.catalogCode()),
        trimToNull(config.schema()) == null ? trimToNull(pingResult.currentSchema()) : trimToNull(config.schema()),
        false,
        new Properties()
    );
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class},
        new ConnectionHandler(state)
    );
  }

  // Bridge factories used by DnaJdbcConnection until sibling concrete classes are created.

  static DatabaseMetaData createDatabaseMetaDataProxy(final DnaJdbcConnection conn) {
    ConnectionState state = new ConnectionState(
        conn.url(), conn.client(),
        conn.databaseProductName(), conn.databaseProductVersion(),
        conn.loginSubject(), conn.currentCatalog(), conn.currentSchema(),
        false, new Properties()
    );
    return (DatabaseMetaData) Proxy.newProxyInstance(
        DatabaseMetaData.class.getClassLoader(),
        new Class<?>[] {DatabaseMetaData.class},
        new DatabaseMetaDataHandler(state, conn)
    );
  }

  static PreparedStatement createPreparedStatementProxy(
      final DnaJdbcConnection conn, final String sql,
      final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability
  ) {
    ConnectionState state = new ConnectionState(
        conn.url(), conn.client(),
        conn.databaseProductName(), conn.databaseProductVersion(),
        conn.loginSubject(), conn.currentCatalog(), conn.currentSchema(),
        false, new Properties()
    );
    return (PreparedStatement) Proxy.newProxyInstance(
        PreparedStatement.class.getClassLoader(),
        new Class<?>[] {PreparedStatement.class},
        new StatementHandler(state, conn, sql, resultSetType, resultSetConcurrency, resultSetHoldability)
    );
  }

  private abstract static class JdbcInvocationHandler implements InvocationHandler {

    @Override
    public final Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
      Object[] safeArgs = args == null ? new Object[0] : args;
      return switch (method.getName()) {
        case "toString" -> getClass().getSimpleName();
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == safeArgs[0];
        case "unwrap" -> unwrap(proxy, safeArgs);
        case "isWrapperFor" -> isWrapperFor(proxy, safeArgs);
        default -> invokeInternal(proxy, method, safeArgs);
      };
    }

    protected abstract Object invokeInternal(Object proxy, Method method, Object[] args) throws Throwable;

    protected static Object unwrap(final Object proxy, final Object[] args) throws SQLException {
      Class<?> targetType = (Class<?>) args[0];
      if (targetType.isInstance(proxy)) {
        return proxy;
      }
      throw new SQLException("不支持 unwrap 到 " + targetType.getName());
    }

    protected static boolean isWrapperFor(final Object proxy, final Object[] args) {
      return ((Class<?>) args[0]).isInstance(proxy);
    }

    protected static SQLFeatureNotSupportedException unsupported(final String feature) {
      return new SQLFeatureNotSupportedException("DNA JDBC 驱动暂不支持: " + feature);
    }

    protected static Object metadataDefaultValue(final Method method) throws SQLException {
      Class<?> returnType = method.getReturnType();
      if (returnType == Void.TYPE) {
        return null;
      }
      if (returnType == Boolean.TYPE) {
        return false;
      }
      if (returnType == Integer.TYPE) {
        return 0;
      }
      if (returnType == Long.TYPE) {
        return 0L;
      }
      if (returnType == String.class) {
        return "";
      }
      if (returnType == ResultSet.class) {
        return ResultSetBuilder.emptyResultSet();
      }
      if (returnType == RowIdLifetime.class) {
        return RowIdLifetime.ROWID_UNSUPPORTED;
      }
      return null;
    }
  }

  private static final class ConnectionHandler extends JdbcInvocationHandler {

    private final ConnectionState state;

    private ConnectionHandler(final ConnectionState state) {
      this.state = state;
    }

    @Override
    protected Object invokeInternal(final Object proxy, final Method method, final Object[] args) throws Throwable {
      ensureOpen();
      return switch (method.getName()) {
        case "createStatement" -> createStatementProxy((Connection) proxy, args);
        case "prepareStatement" -> createPreparedStatementProxy((Connection) proxy, args);
        case "getMetaData" -> getOrCreateMetadata((Connection) proxy);
        case "close" -> {
          state.client.close();
          state.closed = true;
          yield null;
        }
        case "isClosed" -> state.closed;
        case "setReadOnly" -> {
          if (!Boolean.TRUE.equals(args[0])) {
            throw unsupported("写入事务");
          }
          yield null;
        }
        case "isReadOnly" -> true;
        case "setAutoCommit" -> {
          if (!Boolean.TRUE.equals(args[0])) {
            throw unsupported("事务控制");
          }
          yield null;
        }
        case "getAutoCommit" -> true;
        case "commit", "rollback", "setSavepoint", "releaseSavepoint", "prepareCall",
            "createClob", "createBlob", "createNClob", "createSQLXML", "createArrayOf",
            "createStruct" -> throw unsupported(method.getName());
        case "getCatalog" -> state.currentCatalog;
        case "setCatalog" -> {
          state.currentCatalog = trimToNull((String) args[0]);
          yield null;
        }
        case "getSchema" -> state.currentSchema;
        case "setSchema" -> {
          String schema = trimToNull((String) args[0]);
          state.currentSchema = schema;
          yield null;
        }
        case "isValid" -> {
          try {
            state.client.ping();
            yield !state.closed;
          } catch (SQLException ex) {
            yield false;
          }
        }
        case "nativeSQL" -> args[0];
        case "getWarnings" -> null;
        case "clearWarnings" -> null;
        case "getTransactionIsolation" -> Connection.TRANSACTION_NONE;
        case "setTransactionIsolation" -> {
          if (((Integer) args[0]) != Connection.TRANSACTION_NONE) {
            throw unsupported("事务隔离级别");
          }
          yield null;
        }
        case "getHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
        case "setHoldability" -> null;
        case "abort" -> {
          state.client.close();
          state.closed = true;
          yield null;
        }
        case "getClientInfo" -> getClientInfo(args);
        case "setClientInfo" -> {
          setClientInfo(args);
          yield null;
        }
        case "setTypeMap" -> null;
        case "getTypeMap" -> new LinkedHashMap<>();
        case "getNetworkTimeout" -> 0;
        case "setNetworkTimeout", "beginRequest", "endRequest" -> null;
        default -> throw unsupported(method.getName());
      };
    }

    private void ensureOpen() throws SQLException {
      if (state.closed) {
        throw new SQLException("DNA JDBC 连接已关闭");
      }
    }

    private Statement createStatementProxy(final Connection connectionProxy, final Object[] args) throws SQLException {
      int[] options = resolveStatementOptions(args, 0);
      return (Statement) Proxy.newProxyInstance(
          Statement.class.getClassLoader(),
          new Class<?>[] {Statement.class},
          new StatementHandler(state, connectionProxy, null, options[0], options[1], options[2])
      );
    }

    private PreparedStatement createPreparedStatementProxy(
        final Connection connectionProxy,
        final Object[] args
    ) throws SQLException {
      String sql = trimToNull((String) args[0]);
      if (sql == null) {
        throw new SQLException("SQL 不能为空");
      }
      int[] options = resolveStatementOptions(args, 1);
      return (PreparedStatement) Proxy.newProxyInstance(
          PreparedStatement.class.getClassLoader(),
          new Class<?>[] {PreparedStatement.class},
          new StatementHandler(state, connectionProxy, sql, options[0], options[1], options[2])
      );
    }

    private DatabaseMetaData getOrCreateMetadata(final Connection connectionProxy) {
      if (state.databaseMetaData != null) {
        return state.databaseMetaData;
      }
      state.databaseMetaData = (DatabaseMetaData) Proxy.newProxyInstance(
          DatabaseMetaData.class.getClassLoader(),
          new Class<?>[] {DatabaseMetaData.class},
          new DatabaseMetaDataHandler(state, connectionProxy)
      );
      return state.databaseMetaData;
    }

    private Object getClientInfo(final Object[] args) {
      if (args.length == 0) {
        Properties copy = new Properties();
        copy.putAll(state.clientInfo);
        return copy;
      }
      return state.clientInfo.getProperty((String) args[0]);
    }

    private void setClientInfo(final Object[] args) throws SQLClientInfoException {
      if (args.length == 1 && args[0] instanceof Properties properties) {
        state.clientInfo.clear();
        state.clientInfo.putAll(properties);
        return;
      }
      state.clientInfo.setProperty((String) args[0], Objects.toString(args[1], ""));
    }

    private static int[] resolveStatementOptions(final Object[] args, final int offset) throws SQLException {
      int resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE;
      int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
      int holdability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
      if (args.length - offset >= 2
          && args[offset] instanceof Integer type
          && args[offset + 1] instanceof Integer concurrency) {
        if (type != ResultSet.TYPE_FORWARD_ONLY && type != ResultSet.TYPE_SCROLL_INSENSITIVE) {
          throw unsupported("ResultSet 类型 " + type);
        }
        if (concurrency != ResultSet.CONCUR_READ_ONLY) {
          throw unsupported("ResultSet 并发模式 " + concurrency);
        }
        resultSetType = type;
        resultSetConcurrency = concurrency;
      }
      if (args.length - offset >= 3 && args[offset + 2] instanceof Integer value) {
        holdability = value;
      }
      return new int[] {resultSetType, resultSetConcurrency, holdability};
    }
  }

  private static final class StatementHandler extends JdbcInvocationHandler {

    private final ConnectionState state;

    private final Connection connectionProxy;

    private final String preparedSqlTemplate;

    private final int resultSetType;

    private final int resultSetConcurrency;

    private final int resultSetHoldability;

    private final Map<Integer, Object> parameters = new LinkedHashMap<>();

    private boolean closed;

    private int maxRows;

    private int queryTimeout;

    private int fetchSize;

    private boolean closeOnCompletion;

    private ResultSet currentResultSet;

    private StatementHandler(
        final ConnectionState state,
        final Connection connectionProxy,
        final String preparedSqlTemplate,
        final int resultSetType,
        final int resultSetConcurrency,
        final int resultSetHoldability
    ) {
      this.state = state;
      this.connectionProxy = connectionProxy;
      this.preparedSqlTemplate = preparedSqlTemplate;
      this.resultSetType = resultSetType;
      this.resultSetConcurrency = resultSetConcurrency;
      this.resultSetHoldability = resultSetHoldability;
    }

    @Override
    protected Object invokeInternal(final Object proxy, final Method method, final Object[] args) throws Throwable {
      ensureOpen();
      String name = method.getName();
      if (name.startsWith("set") && preparedSqlTemplate != null && args.length >= 2 && args[0] instanceof Integer) {
        setParameter(name, args);
        return null;
      }
      return switch (name) {
        case "executeQuery" -> executeQuery(resolveSql(args));
        case "execute" -> {
          executeQuery(resolveSql(args));
          yield true;
        }
        case "executeUpdate", "executeLargeUpdate" -> throw unsupported("更新语句");
        case "clearParameters" -> {
          parameters.clear();
          yield null;
        }
        case "close" -> {
          closeCurrentResultSet();
          closed = true;
          yield null;
        }
        case "isClosed" -> closed;
        case "getConnection" -> connectionProxy;
        case "getResultSet" -> currentResultSet;
        case "getUpdateCount" -> -1;
        case "getLargeUpdateCount" -> -1L;
        case "getMoreResults" -> false;
        case "getGeneratedKeys" -> ResultSetBuilder.emptyResultSet();
        case "getWarnings" -> null;
        case "clearWarnings", "cancel", "clearBatch", "setCursorName", "setEscapeProcessing",
            "setPoolable" -> null;
        case "addBatch", "executeBatch", "executeLargeBatch" -> throw unsupported(name);
        case "getResultSetType" -> resultSetType;
        case "getResultSetConcurrency" -> resultSetConcurrency;
        case "getResultSetHoldability" -> resultSetHoldability;
        case "setFetchDirection" -> null;
        case "getFetchDirection" -> ResultSet.FETCH_FORWARD;
        case "setFetchSize" -> {
          fetchSize = ((Number) args[0]).intValue();
          yield null;
        }
        case "getFetchSize" -> fetchSize;
        case "setMaxRows" -> {
          maxRows = ((Number) args[0]).intValue();
          yield null;
        }
        case "getMaxRows" -> maxRows;
        case "setLargeMaxRows" -> {
          maxRows = (int) ((Number) args[0]).longValue();
          yield null;
        }
        case "getLargeMaxRows" -> (long) maxRows;
        case "setQueryTimeout" -> {
          queryTimeout = ((Number) args[0]).intValue();
          yield null;
        }
        case "getQueryTimeout" -> queryTimeout;
        case "setMaxFieldSize" -> null;
        case "getMaxFieldSize" -> 0;
        case "closeOnCompletion" -> {
          closeOnCompletion = true;
          yield null;
        }
        case "isCloseOnCompletion" -> closeOnCompletion;
        case "isPoolable" -> false;
        case "getMetaData" -> currentResultSet != null ? currentResultSet.getMetaData() : null;
        default -> throw unsupported(name);
      };
    }

    private void ensureOpen() throws SQLException {
      if (closed) {
        throw new SQLException("Statement 已关闭");
      }
      if (state.closed) {
        throw new SQLException("DNA JDBC 连接已关闭");
      }
    }

    private ResultSet executeQuery(final String sql) throws SQLException {
      closeCurrentResultSet();
      DnaJdbcModels.QueryResult result = state.client.query(state.currentCatalog, sql, state.currentSchema);
      currentResultSet = ResultSetBuilder.fromQueryResult(result, maxRows);
      return currentResultSet;
    }

    private void closeCurrentResultSet() throws SQLException {
      if (currentResultSet != null) {
        currentResultSet.close();
        currentResultSet = null;
      }
    }

    private String resolveSql(final Object[] args) throws SQLException {
      if (args.length > 0 && args[0] instanceof String sql) {
        String normalized = trimToNull(sql);
        if (normalized == null) {
          throw new SQLException("SQL 不能为空");
        }
        return normalized;
      }
      if (preparedSqlTemplate == null) {
        throw new SQLException("SQL 不能为空");
      }
      return renderPreparedSql();
    }

    private void setParameter(final String methodName, final Object[] args) throws SQLException {
      int index = ((Number) args[0]).intValue();
      if (index < 1) {
        throw new SQLException("JDBC 参数位置必须从 1 开始");
      }
      Object value = switch (methodName) {
        case "setNull" -> null;
        case "setBytes" -> args[1];
        default -> args[1];
      };
      parameters.put(index, value);
    }

    private String renderPreparedSql() throws SQLException {
      StringBuilder builder = new StringBuilder(preparedSqlTemplate.length() + 32);
      boolean singleQuoted = false;
      boolean doubleQuoted = false;
      int parameterIndex = 1;
      for (int index = 0; index < preparedSqlTemplate.length(); index++) {
        char current = preparedSqlTemplate.charAt(index);
        if (current == '\'' && !doubleQuoted) {
          builder.append(current);
          if (singleQuoted && index + 1 < preparedSqlTemplate.length()
              && preparedSqlTemplate.charAt(index + 1) == '\'') {
            builder.append(preparedSqlTemplate.charAt(++index));
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
      if (value instanceof java.sql.Date
          || value instanceof java.sql.Time
          || value instanceof java.sql.Timestamp
          || value instanceof java.time.temporal.TemporalAccessor
          || value instanceof java.util.Date
          || value instanceof BigDecimal
          || value instanceof Character
          || value instanceof String) {
        return '\'' + value.toString().replace("'", "''") + '\'';
      }
      throw new SQLException("DNA JDBC PreparedStatement 暂不支持的参数类型: " + value.getClass().getName());
    }
  }

  private static final class DatabaseMetaDataHandler extends JdbcInvocationHandler {

    private final ConnectionState state;

    private final Connection connectionProxy;

    private DatabaseMetaDataHandler(final ConnectionState state, final Connection connectionProxy) {
      this.state = state;
      this.connectionProxy = connectionProxy;
    }

    @Override
    protected Object invokeInternal(final Object proxy, final Method method, final Object[] args) throws Throwable {
      return switch (method.getName()) {
        case "getConnection" -> connectionProxy;
        case "getURL" -> state.url;
        case "getUserName" -> state.loginSubject;
        case "getDriverName" -> DnaJdbcDriver.driverName();
        case "getDriverVersion" -> DnaJdbcDriver.driverVersion();
        case "getDriverMajorVersion" -> 1;
        case "getDriverMinorVersion" -> 0;
        case "getDatabaseProductName" -> state.databaseProductName;
        case "getDatabaseProductVersion" -> state.databaseProductVersion;
        case "getCatalogs" -> ResultSetBuilder.fromTabularResult(state.client.catalogs());
        case "getSchemas" -> handleGetSchemas(args);
        case "getTableTypes" -> ResultSetBuilder.fromTabularResult(state.client.tableTypes());
        case "getTables" -> handleGetTables(args);
        case "getColumns" -> handleGetColumns(args);
        case "getPrimaryKeys" -> handleGetPrimaryKeys(args);
        case "getIndexInfo" -> handleGetIndexInfo(args);
        case "getImportedKeys" -> handleGetImportedKeys(args);
        case "getExportedKeys" -> handleGetExportedKeys(args);
        case "getTypeInfo" -> ResultSetBuilder.fromTabularResult(state.client.typeInfo());
        case "allTablesAreSelectable", "supportsResultSetHoldability",
            "supportsSchemasInDataManipulation", "supportsSchemasInProcedureCalls",
            "supportsSchemasInTableDefinitions", "supportsSchemasInIndexDefinitions",
            "supportsCatalogsInDataManipulation", "supportsCatalogsInProcedureCalls",
            "supportsCatalogsInTableDefinitions", "supportsCatalogsInIndexDefinitions",
            "supportsResultSetType", "supportsResultSetConcurrency",
            "supportsMixedCaseIdentifiers", "supportsMixedCaseQuotedIdentifiers",
            "storesMixedCaseIdentifiers", "storesMixedCaseQuotedIdentifiers" -> true;
        case "isReadOnly", "supportsTransactions", "supportsBatchUpdates",
            "supportsStoredProcedures", "supportsStoredFunctionsUsingCallSyntax",
            "supportsSavepoints", "supportsNamedParameters",
            "nullPlusNonNullIsNull", "usesLocalFiles", "usesLocalFilePerTable",
            "supportsAlterTableWithAddColumn", "supportsAlterTableWithDropColumn",
            "supportsColumnAliasing", "nullsAreSortedHigh", "nullsAreSortedLow",
            "nullsAreSortedAtStart", "nullsAreSortedAtEnd",
            "storesLowerCaseIdentifiers", "storesUpperCaseIdentifiers",
            "storesLowerCaseQuotedIdentifiers", "storesUpperCaseQuotedIdentifiers",
            "supportsMultipleOpenResults", "supportsGetGeneratedKeys",
            "generatedKeyAlwaysReturned", "autoCommitFailureClosesAllResultSets" -> false;
        case "getDefaultTransactionIsolation" -> Connection.TRANSACTION_NONE;
        case "getResultSetHoldability" -> ResultSet.CLOSE_CURSORS_AT_COMMIT;
        case "getJDBCMajorVersion" -> 4;
        case "getJDBCMinorVersion" -> 3;
        case "getCatalogSeparator" -> ".";
        case "getCatalogTerm" -> "catalog";
        case "getSchemaTerm" -> "schema";
        case "getIdentifierQuoteString" -> "\"";
        case "getSearchStringEscape" -> "\\";
        case "getExtraNameCharacters" -> "";
        case "getSQLKeywords" -> "CATALOG,COLUMN,CROSS,CURRENT_CATALOG,CURRENT_SCHEMA,"
            + "FETCH,FIRST,FULL,GROUPING,INNER,INTERSECT,JOIN,LAST,LEFT,LIMIT,MINUS,NATURAL,"
            + "OFFSET,ON,ORDER,OUTER,PARTITION,RIGHT,ROW,ROWS,SCHEMA,TABLE,UNION,USING,VALUE,VALUES,WINDOW";
        case "getNumericFunctions" -> "ABS,ACOS,ASIN,ATAN,ATAN2,CEILING,COS,COT,DEGREES,EXP,"
            + "FLOOR,LOG,LOG10,MOD,PI,POWER,RADIANS,RAND,ROUND,SIGN,SIN,SQRT,TAN,TRUNCATE";
        case "getStringFunctions" -> "ASCII,CHAR,CHAR_LENGTH,CHARACTER_LENGTH,CONCAT,INSERT,"
            + "LCASE,LEFT,LENGTH,LOCATE,LTRIM,REPEAT,REPLACE,RIGHT,RTRIM,SPACE,SUBSTRING,UCASE,UPPER,LOWER,TRIM";
        case "getSystemFunctions" -> "CURRENT_USER,SESSION_USER,USER";
        case "getTimeDateFunctions" -> "CURRENT_DATE,CURRENT_TIME,CURRENT_TIMESTAMP,"
            + "DAYNAME,DAYOFMONTH,DAYOFWEEK,DAYOFYEAR,EXTRACT,HOUR,MINUTE,MONTH,MONTHNAME,"
            + "NOW,QUARTER,SECOND,WEEK,YEAR";
        default -> metadataDefaultValue(method);
      };
    }

    private Object handleGetSchemas(final Object[] args) throws SQLException {
      String catalogPattern = trimToNull(args.length >= 1 ? (String) args[0] : null);
      String schemaPattern = trimToNull(args.length >= 2 ? (String) args[1] : null);
      return ResultSetBuilder.fromTabularResult(state.client.schemas(catalogPattern, schemaPattern));
    }

    private Object handleGetTables(final Object[] args) throws SQLException {
      String catalogPattern = trimToNull(args.length >= 1 ? (String) args[0] : null);
      String schemaPattern = trimToNull(args.length >= 2 ? (String) args[1] : null);
      String tablePattern = args.length >= 3 ? (String) args[2] : null;
      List<String> types = null;
      if (args.length >= 4 && args[3] instanceof String[] values) {
        types = List.of(values);
      }
      return ResultSetBuilder.fromTabularResult(state.client.tables(catalogPattern, schemaPattern, tablePattern, types));
    }

    private Object handleGetColumns(final Object[] args) throws SQLException {
      String catalogPattern = trimToNull(args.length >= 1 ? (String) args[0] : null);
      String schemaPattern = trimToNull(args.length >= 2 ? (String) args[1] : null);
      String tablePattern = args.length >= 3 ? (String) args[2] : null;
      String columnPattern = args.length >= 4 ? (String) args[3] : null;
      return ResultSetBuilder.fromTabularResult(state.client.columns(catalogPattern, schemaPattern, tablePattern, columnPattern));
    }

    private Object handleGetPrimaryKeys(final Object[] args) throws SQLException {
      String catalog = trimToNull(args.length >= 1 ? (String) args[0] : null);
      String schema = trimToNull(args.length >= 2 ? (String) args[1] : null);
      String table = args.length >= 3 ? (String) args[2] : null;
      return ResultSetBuilder.fromTabularResult(state.client.primaryKeys(catalog, schema, table));
    }

    private Object handleGetIndexInfo(final Object[] args) throws SQLException {
      String catalog = trimToNull(args.length >= 1 ? (String) args[0] : null);
      String schema = trimToNull(args.length >= 2 ? (String) args[1] : null);
      String table = args.length >= 3 ? (String) args[2] : null;
      boolean unique = args.length >= 4 && Boolean.TRUE.equals(args[3]);
      boolean approximate = args.length < 5 || !Boolean.FALSE.equals(args[4]);
      return ResultSetBuilder.fromTabularResult(state.client.indexInfo(catalog, schema, table, unique, approximate));
    }

    private Object handleGetImportedKeys(final Object[] args) throws SQLException {
      String catalog = trimToNull(args.length >= 1 ? (String) args[0] : null);
      String schema = trimToNull(args.length >= 2 ? (String) args[1] : null);
      String table = args.length >= 3 ? (String) args[2] : null;
      return ResultSetBuilder.fromTabularResult(state.client.importedKeys(catalog, schema, table));
    }

    private Object handleGetExportedKeys(final Object[] args) throws SQLException {
      String catalog = trimToNull(args.length >= 1 ? (String) args[0] : null);
      String schema = trimToNull(args.length >= 2 ? (String) args[1] : null);
      String table = args.length >= 3 ? (String) args[2] : null;
      return ResultSetBuilder.fromTabularResult(state.client.exportedKeys(catalog, schema, table));
    }
  }

  private static final class ConnectionState {

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

    private ConnectionState(
        final String url,
        final DnaJdbcClient client,
        final String databaseProductName,
        final String databaseProductVersion,
        final String loginSubject,
        final String currentCatalog,
        final String currentSchema,
        final boolean closed,
        final Properties clientInfo
    ) {
      this.url = url;
      this.client = client;
      this.databaseProductName = databaseProductName;
      this.databaseProductVersion = databaseProductVersion;
      this.loginSubject = loginSubject;
      this.currentCatalog = currentCatalog;
      this.currentSchema = currentSchema;
      this.closed = closed;
      this.clientInfo = clientInfo;
    }
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
