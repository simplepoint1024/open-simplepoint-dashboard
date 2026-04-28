package org.simplepoint.plugin.dna.core.service.impl;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

/**
 * Shared infrastructure for JDBC metadata service implementations.
 */
abstract class AbstractJdbcMetadataService {

  final JdbcDataSourceDefinitionService dataSourceService;

  final JdbcDriverDefinitionRepository driverRepository;

  final JdbcDialectManagementService dialectManagementService;

  AbstractJdbcMetadataService(
      final JdbcDataSourceDefinitionService dataSourceService,
      final JdbcDriverDefinitionRepository driverRepository,
      final JdbcDialectManagementService dialectManagementService
  ) {
    this.dataSourceService = dataSourceService;
    this.driverRepository = driverRepository;
    this.dialectManagementService = dialectManagementService;
  }

  <T> T withContext(
      final String dataSourceId,
      final String targetCatalog,
      final SqlCallback<T> callback
  ) {
    JdbcDataSourceDefinition dataSource = dataSourceService.findActiveById(dataSourceId)
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在: " + dataSourceId));
    JdbcDriverDefinition driver = driverRepository.findActiveById(dataSource.getDriverId())
        .orElseThrow(() -> new IllegalArgumentException("驱动不存在: " + dataSource.getDriverId()));
    try (Connection baseConnection = dataSourceService.requireSimpleDataSource(dataSourceId).getConnection()) {
      RuntimeContext runtimeContext = createRuntimeContext(baseConnection, driver, dataSource);
      String normalizedTargetCatalog = trimToNull(targetCatalog);
      if (normalizedTargetCatalog != null
          && runtimeContext.dialect().requiresCatalogConnection(normalizedTargetCatalog, runtimeContext.supportContext())) {
        String targetJdbcUrl = runtimeContext.dialect().remapJdbcUrlCatalog(
            dataSource.getJdbcUrl(),
            normalizedTargetCatalog,
            runtimeContext.supportContext()
        );
        try (Connection targetConnection = dataSourceService.createTransientSimpleDataSource(dataSourceId, targetJdbcUrl)
            .getConnection()) {
          return callback.execute(createRuntimeContext(targetConnection, driver, dataSource));
        }
      }
      return callback.execute(runtimeContext);
    } catch (SQLException ex) {
      throw new IllegalStateException("访问数据库元数据失败: " + ex.getMessage(), ex);
    }
  }

  <T> T withTransaction(
      final String dataSourceId,
      final String targetCatalog,
      final SqlCallback<T> callback
  ) {
    return withContext(dataSourceId, targetCatalog, context -> {
      boolean previousAutoCommit = context.connection().getAutoCommit();
      try {
        context.connection().setAutoCommit(false);
        T result = callback.execute(context);
        context.connection().commit();
        return result;
      } catch (Exception ex) {
        try {
          context.connection().rollback();
        } catch (SQLException rollbackEx) {
          ex.addSuppressed(rollbackEx);
        }
        if (ex instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw new IllegalStateException("执行元数据管理操作失败: " + ex.getMessage(), ex);
      } finally {
        context.connection().setAutoCommit(previousAutoCommit);
      }
    });
  }

  private RuntimeContext createRuntimeContext(
      final Connection connection,
      final JdbcDriverDefinition driver,
      final JdbcDataSourceDefinition dataSource
  ) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    JdbcDatabaseDialect.SupportContext supportContext = new JdbcDatabaseDialect.SupportContext(
        driver.getDatabaseType(),
        driver.getDriverClassName(),
        metaData.getDatabaseProductName(),
        metaData.getDatabaseProductVersion(),
        trimToNull(connection.getCatalog()),
        trimToNull(connection.getSchema()),
        trimToNull(metaData.getIdentifierQuoteString()),
        parseProperties(dataSource.getConnectionProperties())
    );
    JdbcDatabaseDialect dialect = dialectManagementService.resolveDialect(supportContext)
        .orElseThrow(() -> new IllegalStateException("未找到可用的数据库方言"));
    return new RuntimeContext(connection, metaData, supportContext, dialect);
  }

  static String extractExplicitCatalog(final List<JdbcMetadataModels.PathSegment> path) {
    return normalizePath(path).stream()
        .filter(segment -> JdbcMetadataModels.NodeType.DATABASE.equals(segment.type())
            || JdbcMetadataModels.NodeType.CATALOG.equals(segment.type()))
        .map(JdbcMetadataModels.PathSegment::name)
        .map(AbstractJdbcMetadataService::trimToNull)
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse(null);
  }

  static String resolveTargetCatalogForDrop(final List<JdbcMetadataModels.PathSegment> path) {
    List<JdbcMetadataModels.PathSegment> normalizedPath = normalizePath(path);
    if (normalizedPath.isEmpty()) {
      return null;
    }
    JdbcMetadataModels.NodeType leafType = normalizedPath.get(normalizedPath.size() - 1).type();
    if (JdbcMetadataModels.NodeType.DATABASE.equals(leafType)
        || JdbcMetadataModels.NodeType.CATALOG.equals(leafType)) {
      return null;
    }
    return extractExplicitCatalog(normalizedPath);
  }

  static void execute(final Connection connection, final String sql) {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException ex) {
      throw new IllegalStateException("执行 SQL 失败: " + ex.getMessage(), ex);
    }
  }

  static ResolvedPath requireRelationPath(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    if (!JdbcMetadataModels.NodeType.TABLE.equals(resolved.leafType())
        && !JdbcMetadataModels.NodeType.VIEW.equals(resolved.leafType())) {
      throw new IllegalArgumentException("当前路径必须指向数据表或视图");
    }
    return resolved;
  }

  static ResolvedPath requireTablePath(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    if (!JdbcMetadataModels.NodeType.TABLE.equals(resolved.leafType())) {
      throw new IllegalArgumentException("当前路径必须指向数据表");
    }
    return resolved;
  }

  static NamespacePath resolveNamespace(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    return switch (resolved.leafType()) {
      case ROOT -> new NamespacePath(context.currentCatalog(), context.currentSchema());
      case DATABASE, CATALOG -> new NamespacePath(resolved.objectName(), context.currentSchema());
      case SCHEMA -> new NamespacePath(firstNonBlank(resolved.catalog(), context.currentCatalog()), resolved.objectName());
      default -> throw new IllegalArgumentException("当前路径必须指向数据库、catalog、schema 或根节点");
    };
  }

  static void validateNamespaceParent(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    ResolvedPath resolved = resolvePath(path, context);
    if (JdbcMetadataModels.NodeType.TABLE.equals(resolved.leafType())
        || JdbcMetadataModels.NodeType.VIEW.equals(resolved.leafType())
        || JdbcMetadataModels.NodeType.COLUMN.equals(resolved.leafType())) {
      throw new IllegalArgumentException("命名空间只能创建在根节点或命名空间节点下");
    }
  }

  static ResolvedPath resolvePath(
      final List<JdbcMetadataModels.PathSegment> rawPath,
      final JdbcDatabaseDialect.SupportContext context
  ) {
    List<JdbcMetadataModels.PathSegment> path = normalizePath(rawPath);
    if (path.isEmpty()) {
      return new ResolvedPath(List.of(), null, null, null, null, JdbcMetadataModels.NodeType.ROOT);
    }
    String catalog = null;
    String schema = null;
    String objectName = null;
    String columnName = null;
    JdbcMetadataModels.NodeType leafType = JdbcMetadataModels.NodeType.ROOT;
    for (JdbcMetadataModels.PathSegment segment : path) {
      String name = requireName(segment.name(), "元数据路径名称不能为空");
      leafType = segment.type();
      switch (segment.type()) {
        case DATABASE, CATALOG -> {
          catalog = name;
          objectName = name;
        }
        case SCHEMA -> {
          schema = name;
          objectName = name;
        }
        case TABLE, VIEW -> objectName = name;
        case COLUMN -> columnName = name;
        case ROOT -> {
        }
        default -> {
        }
      }
    }
    if ((JdbcMetadataModels.NodeType.TABLE.equals(leafType) || JdbcMetadataModels.NodeType.VIEW.equals(leafType))
        && catalog == null) {
      catalog = context.currentCatalog();
    }
    if ((JdbcMetadataModels.NodeType.TABLE.equals(leafType) || JdbcMetadataModels.NodeType.VIEW.equals(leafType))
        && schema == null) {
      schema = context.currentSchema();
    }
    return new ResolvedPath(path, trimToNull(catalog), trimToNull(schema), objectName, columnName, leafType);
  }

  static JdbcMetadataModels.ColumnDefinition requireColumn(final JdbcMetadataModels.ColumnDefinition column) {
    if (column == null) {
      throw new IllegalArgumentException("字段定义不能为空");
    }
    requireName(column.name(), "字段名称不能为空");
    requireName(column.typeName(), "字段类型不能为空");
    return column;
  }

  static JdbcMetadataModels.ConstraintDefinition requireConstraint(
      final JdbcMetadataModels.ConstraintDefinition constraint
  ) {
    if (constraint == null) {
      throw new IllegalArgumentException("约束定义不能为空");
    }
    if (constraint.type() == null) {
      throw new IllegalArgumentException("约束类型不能为空");
    }
    return constraint;
  }

  static List<JdbcMetadataModels.ConstraintDefinition> safeConstraints(
      final List<JdbcMetadataModels.ConstraintDefinition> constraints
  ) {
    return constraints == null ? List.of() : constraints;
  }

  static List<JdbcMetadataModels.PathSegment> normalizePath(final List<JdbcMetadataModels.PathSegment> path) {
    if (path == null || path.isEmpty()) {
      return List.of();
    }
    return path.stream()
        .filter(segment -> segment != null && segment.type() != null)
        .toList();
  }

  static String typeLabel(final JdbcMetadataModels.NodeType type) {
    return switch (type) {
      case ROOT -> "根节点";
      case DATABASE -> "数据库";
      case CATALOG -> "Catalog";
      case SCHEMA -> "Schema";
      case TABLE -> "数据表";
      case VIEW -> "视图";
      case COLUMN -> "字段";
    };
  }

  static Comparator<JdbcMetadataModels.TreeNode> treeNodeComparator() {
    return Comparator.comparing((JdbcMetadataModels.TreeNode node) -> node.type().ordinal())
        .thenComparing(JdbcMetadataModels.TreeNode::title, String.CASE_INSENSITIVE_ORDER);
  }

  static List<JdbcMetadataModels.PathSegment> append(
      final List<JdbcMetadataModels.PathSegment> path,
      final JdbcMetadataModels.PathSegment segment
  ) {
    List<JdbcMetadataModels.PathSegment> copy = new ArrayList<>(path == null ? List.of() : path);
    copy.add(segment);
    return List.copyOf(copy);
  }

  static String key(final Collection<JdbcMetadataModels.PathSegment> path) {
    return path.stream()
        .map(segment -> segment.type().name() + ":" + segment.name())
        .reduce((left, right) -> left + "/" + right)
        .orElse(JdbcMetadataModels.NodeType.ROOT.name());
  }

  static boolean canCreateNamespaceBelow(
      final JdbcDatabaseDialect dialect,
      final JdbcMetadataModels.NodeType currentType
  ) {
    if (JdbcMetadataModels.NodeType.DATABASE.equals(currentType)
        || JdbcMetadataModels.NodeType.CATALOG.equals(currentType)) {
      return dialect.supportsNamespaceCreate(JdbcMetadataModels.NodeType.SCHEMA);
    }
    return false;
  }

  static String displayType(final JdbcMetadataModels.ColumnDefinition column) {
    if (column == null) {
      return null;
    }
    if (column.size() == null || column.size() <= 0) {
      return column.typeName();
    }
    if (column.scale() == null || column.scale() < 0) {
      return column.typeName() + "(" + column.size() + ")";
    }
    return column.typeName() + "(" + column.size() + "," + column.scale() + ")";
  }

  static String exactMetadataPattern(
      final RuntimeContext context,
      final String value
  ) throws SQLException {
    String normalized = trimToNull(value);
    if (normalized == null || context == null || context.metaData() == null) {
      return normalized;
    }
    String escape = trimToNull(context.metaData().getSearchStringEscape());
    if (escape == null) {
      return normalized;
    }
    return normalized
        .replace(escape, escape + escape)
        .replace("%", escape + "%")
        .replace("_", escape + "_");
  }

  static List<String> extractStringValues(
      final JdbcDatabaseDialect.MetadataResult metadata,
      final String columnName
  ) {
    int columnIndex = findColumnIndex(metadata, columnName);
    if (columnIndex < 0 || metadata == null) {
      return List.of();
    }
    return metadata.rows().stream()
        .map(row -> columnIndex < row.size() ? trimToNull(Objects.toString(row.get(columnIndex), null)) : null)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  static String stringValue(
      final JdbcDatabaseDialect.MetadataResult metadata,
      final List<Object> row,
      final String columnName
  ) {
    int columnIndex = findColumnIndex(metadata, columnName);
    return columnIndex >= 0 && row != null && columnIndex < row.size()
        ? trimToNull(Objects.toString(row.get(columnIndex), null))
        : null;
  }

  static Integer integerValue(
      final JdbcDatabaseDialect.MetadataResult metadata,
      final List<Object> row,
      final String columnName
  ) {
    int columnIndex = findColumnIndex(metadata, columnName);
    if (columnIndex < 0 || row == null || columnIndex >= row.size()) {
      return null;
    }
    Object value = row.get(columnIndex);
    if (value instanceof Number number) {
      return number.intValue();
    }
    String text = trimToNull(Objects.toString(value, null));
    return text == null ? null : Integer.valueOf(text);
  }

  static Boolean nullableValue(
      final JdbcDatabaseDialect.MetadataResult metadata,
      final List<Object> row,
      final String columnName
  ) {
    Integer nullable = integerValue(metadata, row, columnName);
    if (nullable == null) {
      return null;
    }
    return nullable == DatabaseMetaData.columnNullable;
  }

  static int findColumnIndex(
      final JdbcDatabaseDialect.MetadataResult metadata,
      final String columnName
  ) {
    List<JdbcDatabaseDialect.MetadataColumn> columns = metadata == null ? List.of() : metadata.columns();
    for (int index = 0; index < columns.size(); index++) {
      JdbcDatabaseDialect.MetadataColumn column = columns.get(index);
      if (column != null && columnName.equalsIgnoreCase(column.name())) {
        return index;
      }
    }
    return -1;
  }

  static Boolean parseYesNo(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return "YES".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
  }

  static Map<String, String> parseProperties(final String text) {
    if (text == null || text.isBlank()) {
      return Map.of();
    }
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(text));
    } catch (IOException ex) {
      throw new IllegalArgumentException("连接属性格式不正确: " + ex.getMessage(), ex);
    }
    Map<String, String> values = new LinkedHashMap<>();
    properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
    return Map.copyOf(values);
  }

  static String requireName(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  static String firstNonBlank(final String first, final String second) {
    return trimToNull(first) != null ? trimToNull(first) : trimToNull(second);
  }

  @FunctionalInterface
  interface SqlCallback<T> {
    T execute(RuntimeContext context) throws SQLException;
  }

  record RuntimeContext(
      Connection connection,
      DatabaseMetaData metaData,
      JdbcDatabaseDialect.SupportContext supportContext,
      JdbcDatabaseDialect dialect
  ) {
  }

  record ResolvedPath(
      List<JdbcMetadataModels.PathSegment> path,
      String catalog,
      String schema,
      String objectName,
      String columnName,
      JdbcMetadataModels.NodeType leafType
  ) {
  }

  record NamespacePath(
      String catalog,
      String schema
  ) {
  }
}
