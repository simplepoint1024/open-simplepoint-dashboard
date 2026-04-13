package org.simplepoint.data.calcite.core.query;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * Default read-only Calcite execution engine backed by a transient {@code jdbc:calcite:} session.
 */
public class DefaultCalciteQueryEngine implements CalciteQueryEngine {

  private static final String CALCITE_JDBC_URL = "jdbc:calcite:";

  private static final int LOB_PREVIEW_LENGTH = 4096;

  private static final List<PushdownOperator> PUSH_DOWN_OPERATORS = List.of(
      new PushdownOperator("JdbcProject", "Project"),
      new PushdownOperator("JdbcFilter", "Filter"),
      new PushdownOperator("JdbcAggregate", "Aggregate"),
      new PushdownOperator("JdbcSort", "Sort"),
      new PushdownOperator("JdbcJoin", "Join"),
      new PushdownOperator("JdbcUnion", "Union"),
      new PushdownOperator("JdbcCalc", "Calc")
  );

  private static final List<String> PLATFORM_JOIN_OPERATORS = List.of(
      "EnumerableHashJoin",
      "EnumerableMergeJoin",
      "EnumerableNestedLoopJoin",
      "EnumerableCorrelate"
  );

  /** {@inheritDoc} */
  @Override
  public CalciteQueryAnalysis explain(
      final CalciteQueryRequest request,
      final CalciteSchemaConfigurer schemaConfigurer
  ) {
    CalciteQueryRequest normalizedRequest = normalizeRequest(request);
    validateReadOnlyQuery(normalizedRequest.sql());
    return withSession(normalizedRequest.defaultSchema(), schemaConfigurer, session -> {
      CapturedValue<CalciteQueryAnalysis> captured = captureBackendQueries(() -> explainInternal(
          session.connection(),
          normalizedRequest.timeoutMs(),
          normalizedRequest.sql()
      ));
      return enrichAnalysis(captured.value(), captured.capturedQueries());
    });
  }

  /** {@inheritDoc} */
  @Override
  public CalciteQueryResult execute(
      final CalciteQueryRequest request,
      final CalciteSchemaConfigurer schemaConfigurer
  ) {
    CalciteQueryRequest normalizedRequest = normalizeRequest(request);
    validateReadOnlyQuery(normalizedRequest.sql());
    return withSession(normalizedRequest.defaultSchema(), schemaConfigurer, session -> {
      CapturedValue<ExecutionPayload> captured = captureBackendQueries(() -> {
        CalciteQueryAnalysis analysis = explainInternal(
            session.connection(),
            normalizedRequest.timeoutMs(),
            normalizedRequest.sql()
        );
        long startedAt = System.nanoTime();
        List<Object> params = normalizedRequest.parameters();
        boolean hasParams = params != null && !params.isEmpty();
        try {
          ResultSet resultSet;
          Statement statement;
          if (hasParams) {
            java.sql.PreparedStatement ps = session.connection().prepareStatement(normalizedRequest.sql());
            ps.setQueryTimeout(toQueryTimeoutSeconds(normalizedRequest.timeoutMs()));
            ps.setMaxRows(toStatementMaxRows(normalizedRequest.maxRows()));
            for (int i = 0; i < params.size(); i++) {
              Object value = params.get(i);
              if (value == null) {
                ps.setNull(i + 1, java.sql.Types.NULL);
              } else {
                ps.setObject(i + 1, value);
              }
            }
            resultSet = ps.executeQuery();
            statement = ps;
          } else {
            Statement st = session.connection().createStatement();
            st.setQueryTimeout(toQueryTimeoutSeconds(normalizedRequest.timeoutMs()));
            st.setMaxRows(toStatementMaxRows(normalizedRequest.maxRows()));
            resultSet = st.executeQuery(normalizedRequest.sql());
            statement = st;
          }
          try (statement; resultSet) {
            ExtractedRows extracted = extractRows(resultSet, normalizedRequest.maxRows());
            return new ExecutionPayload(extracted, toElapsedMs(startedAt), analysis);
          }
        } catch (SQLException ex) {
          throw new IllegalStateException("Calcite 查询执行失败: " + rootMessage(ex), ex);
        }
      });
      CalciteQueryAnalysis analysis = enrichAnalysis(captured.value().analysis(), captured.capturedQueries());
      return new CalciteQueryResult(
          captured.value().extractedRows().columns(),
          captured.value().extractedRows().rows(),
          captured.value().extractedRows().truncated(),
          captured.value().extractedRows().returnedRows(),
          captured.value().executionTimeMs(),
          analysis
      );
    });
  }

  private static CalciteQueryRequest normalizeRequest(final CalciteQueryRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("查询请求不能为空");
    }
    String sql = trimToNull(request.sql());
    if (sql == null) {
      throw new IllegalArgumentException("SQL 不能为空");
    }
    sql = trimTrailingSemicolon(sql);
    if (request.maxRows() < 1) {
      throw new IllegalArgumentException("maxRows 必须大于 0");
    }
    if (request.timeoutMs() < 1) {
      throw new IllegalArgumentException("timeoutMs 必须大于 0");
    }
    return new CalciteQueryRequest(
        sql,
        trimToNull(request.defaultSchema()),
        request.maxRows(),
        request.timeoutMs(),
        request.parameters()
    );
  }

  private static void validateReadOnlyQuery(final String sql) {
    try {
      SqlParser parser = SqlParser.create(sql);
      SqlNodeList statements = parser.parseStmtList();
      if (statements.size() != 1) {
        throw new IllegalArgumentException("只允许执行单条 SQL");
      }
      SqlNode statement = statements.get(0);
      if (statement == null || !statement.getKind().belongsTo(SqlKind.QUERY)) {
        throw new IllegalArgumentException("仅支持只读查询 SQL");
      }
    } catch (SqlParseException ex) {
      throw new IllegalArgumentException("SQL 解析失败: " + rootMessage(ex), ex);
    }
  }

  private static CalciteQueryAnalysis explainInternal(
      final Connection connection,
      final int timeoutMs,
      final String sql
  ) {
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(toQueryTimeoutSeconds(timeoutMs));
      try (ResultSet resultSet = statement.executeQuery("EXPLAIN PLAN FOR " + sql)) {
        String planText = readExplainPlan(resultSet);
        return new CalciteQueryAnalysis(
            planText,
            detectPushedDownOperators(planText),
            detectPlatformJoin(planText)
        );
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("Calcite 执行计划生成失败: " + rootMessage(ex), ex);
    }
  }

  private static CalciteQueryAnalysis enrichAnalysis(
      final CalciteQueryAnalysis analysis,
      final List<String> capturedQueries
  ) {
    if (analysis == null) {
      return null;
    }
    if (capturedQueries == null || capturedQueries.isEmpty()) {
      return analysis;
    }
    return new CalciteQueryAnalysis(
        analysis.planText(),
        analysis.pushedDownOperators(),
        analysis.platformJoin(),
        capturedQueries
    );
  }

  private static String readExplainPlan(final ResultSet resultSet) throws SQLException {
    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();
    StringBuilder builder = new StringBuilder();
    boolean firstRow = true;
    while (resultSet.next()) {
      if (!firstRow) {
        builder.append(System.lineSeparator());
      }
      firstRow = false;
      for (int index = 1; index <= columnCount; index++) {
        if (index > 1) {
          builder.append('\t');
        }
        Object value = resultSet.getObject(index);
        if (value != null) {
          builder.append(value);
        }
      }
    }
    return builder.toString().trim();
  }

  private static List<String> detectPushedDownOperators(final String planText) {
    Set<String> operators = new LinkedHashSet<>();
    String normalized = planText == null ? "" : planText;
    for (PushdownOperator operator : PUSH_DOWN_OPERATORS) {
      if (normalized.contains(operator.planToken())) {
        operators.add(operator.displayName());
      }
    }
    return List.copyOf(operators);
  }

  private static boolean detectPlatformJoin(final String planText) {
    String normalized = planText == null ? "" : planText;
    return PLATFORM_JOIN_OPERATORS.stream().anyMatch(normalized::contains);
  }

  private static ExtractedRows extractRows(
      final ResultSet resultSet,
      final int maxRows
  ) throws SQLException {
    ResultSetMetaData metaData = resultSet.getMetaData();
    int columnCount = metaData.getColumnCount();
    List<CalciteQueryColumn> columns = new ArrayList<>(columnCount);
    for (int index = 1; index <= columnCount; index++) {
      String label = trimToNull(metaData.getColumnLabel(index));
      columns.add(new CalciteQueryColumn(
          label == null ? metaData.getColumnName(index) : label,
          trimToNull(metaData.getColumnTypeName(index)),
          metaData.getColumnType(index)
      ));
    }

    List<List<Object>> rows = new ArrayList<>();
    long returnedRows = 0;
    boolean truncated = false;
    while (resultSet.next()) {
      if (returnedRows == maxRows) {
        truncated = true;
        break;
      }
      List<Object> row = new ArrayList<>(columnCount);
      for (int index = 1; index <= columnCount; index++) {
        row.add(normalizeCellValue(resultSet.getObject(index)));
      }
      rows.add(Collections.unmodifiableList(row));
      returnedRows++;
    }
    return new ExtractedRows(List.copyOf(columns), rows, truncated, returnedRows);
  }

  private static Object normalizeCellValue(final Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof Timestamp
        || value instanceof java.sql.Date
        || value instanceof Time
        || value instanceof java.util.Date) {
      return value.toString();
    }
    if (value instanceof Clob clob) {
      return readClob(clob);
    }
    if (value instanceof Blob blob) {
      return readBlob(blob);
    }
    if (value instanceof java.sql.Array sqlArray) {
      return normalizeArray(sqlArray.getArray());
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    if (value.getClass().isArray()) {
      return normalizeArray(value);
    }
    return value;
  }

  private static List<Object> normalizeArray(final Object value) throws SQLException {
    int length = Array.getLength(value);
    List<Object> values = new ArrayList<>(length);
    for (int index = 0; index < length; index++) {
      values.add(normalizeCellValue(Array.get(value, index)));
    }
    return Collections.unmodifiableList(values);
  }

  private static String readClob(final Clob clob) throws SQLException {
    long length = Math.min(clob.length(), LOB_PREVIEW_LENGTH);
    return clob.getSubString(1, (int) length);
  }

  private static String readBlob(final Blob blob) throws SQLException {
    long length = Math.min(blob.length(), LOB_PREVIEW_LENGTH);
    return Base64.getEncoder().encodeToString(blob.getBytes(1, (int) length));
  }

  private static int toStatementMaxRows(final int maxRows) {
    return maxRows == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxRows + 1;
  }

  private static int toQueryTimeoutSeconds(final int timeoutMs) {
    return Math.max(1, (int) Math.ceil(timeoutMs / 1000.0d));
  }

  private static long toElapsedMs(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private static String trimTrailingSemicolon(final String value) {
    String normalized = value;
    while (normalized.endsWith(";")) {
      normalized = normalized.substring(0, normalized.length() - 1).trim();
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String rootMessage(final Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = trimToNull(current.getMessage());
    return message == null ? current.getClass().getSimpleName() : message;
  }

  private static <T> CapturedValue<T> captureBackendQueries(final QueryCallback<T> callback) {
    if (callback == null) {
      throw new IllegalArgumentException("callback 不能为空");
    }
    List<String> capturedQueries = new ArrayList<>();
    try (Hook.Closeable closeable = Hook.QUERY_PLAN.addThread(query -> {
      if (query instanceof String sql) {
        String normalized = trimToNull(sql);
        if (normalized != null) {
          capturedQueries.add(normalized);
        }
      }
    })) {
      return new CapturedValue<>(callback.execute(), deduplicateQueries(capturedQueries));
    } catch (RuntimeException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("采集 JDBC 下推 SQL 失败: " + rootMessage(ex), ex);
    }
  }

  private static List<String> deduplicateQueries(final List<String> queries) {
    if (queries == null || queries.isEmpty()) {
      return List.of();
    }
    return List.copyOf(new LinkedHashSet<>(queries));
  }

  private static <T> T withSession(
      final String defaultSchema,
      final CalciteSchemaConfigurer schemaConfigurer,
      final SessionCallback<T> callback
  ) {
    if (schemaConfigurer == null) {
      throw new IllegalArgumentException("schemaConfigurer 不能为空");
    }
    Properties properties = new Properties();
    properties.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");
    try (Connection connection = DriverManager.getConnection(CALCITE_JDBC_URL, properties)) {
      CalciteConnection calciteConnection = connection.unwrap(CalciteConnection.class);
      SchemaPlus rootSchema = calciteConnection.getRootSchema();
      schemaConfigurer.configure(rootSchema);
      if (defaultSchema != null) {
        calciteConnection.setSchema(defaultSchema);
      }
      return callback.execute(new CalciteSession(connection, calciteConnection));
    } catch (SQLException ex) {
      throw new IllegalStateException("创建 Calcite 会话失败: " + rootMessage(ex), ex);
    }
  }

  @FunctionalInterface
  private interface SessionCallback<T> {
    T execute(CalciteSession session);
  }

  @FunctionalInterface
  private interface QueryCallback<T> {
    T execute() throws Exception;
  }

  private record CalciteSession(
      Connection connection,
      CalciteConnection calciteConnection
  ) {
  }

  private record ExtractedRows(
      List<CalciteQueryColumn> columns,
      List<List<Object>> rows,
      boolean truncated,
      long returnedRows
  ) {
  }

  private record ExecutionPayload(
      ExtractedRows extractedRows,
      long executionTimeMs,
      CalciteQueryAnalysis analysis
  ) {
  }

  private record CapturedValue<T>(
      T value,
      List<String> capturedQueries
  ) {
  }

  private record PushdownOperator(
      String planToken,
      String displayName
  ) {
  }
}
