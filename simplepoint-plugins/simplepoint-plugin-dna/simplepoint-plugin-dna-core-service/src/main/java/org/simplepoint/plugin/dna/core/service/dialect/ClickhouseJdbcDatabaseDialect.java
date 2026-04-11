package org.simplepoint.plugin.dna.core.service.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.spi.JdbcTypeMapping;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.service.dialect.type.ClickhouseJdbcTypeMapping;

/**
 * JDBC dialect for ClickHouse.
 */
public class ClickhouseJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "clickhouse";
  }

  @Override
  public String name() {
    return "ClickHouse";
  }

  @Override
  public String description() {
    return "ClickHouse analytical database dialect";
  }

  @Override
  public int order() {
    return 12;
  }

  @Override
  public JdbcTypeMapping typeMapping() {
    return ClickhouseJdbcTypeMapping.INSTANCE;
  }

  @Override
  public boolean supports(final SupportContext context) {
    String databaseType = normalize(context.databaseType());
    String driverClassName = normalize(context.driverClassName());
    String productName = normalize(context.productName());
    return databaseType.contains("clickhouse")
        || driverClassName.contains("clickhouse")
        || productName.contains("clickhouse");
  }

  @Override
  public List<String> visibleCatalogs(final List<String> catalogs, final SupportContext context) {
    return List.of();
  }

  @Override
  public String metadataCatalog(final String catalog, final SupportContext context) {
    return null;
  }

  @Override
  public boolean supportsNamespaceCreate(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.SCHEMA.equals(type);
  }

  @Override
  public boolean supportsNamespaceDrop(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.SCHEMA.equals(type);
  }

  @Override
  public boolean supportsConstraintManagement() {
    return false;
  }

  @Override
  public String qualifyName(
      final String catalog,
      final String schema,
      final String objectName,
      final SupportContext context
  ) {
    String database = trimToNull(schema);
    if (database == null) {
      database = trimToNull(catalog);
    }
    if (database == null) {
      return quoteIdentifier(objectName, context);
    }
    return quoteIdentifier(database, context) + "." + quoteIdentifier(objectName, context);
  }

  @Override
  public String buildCreateNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name
  ) {
    return buildCreateNamespaceSql(type, name, null);
  }

  @Override
  public String buildCreateNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final SupportContext context
  ) {
    if (JdbcMetadataModels.NodeType.SCHEMA.equals(type)) {
      return "CREATE DATABASE " + quoteIdentifier(name, context);
    }
    return super.buildCreateNamespaceSql(type, name, context);
  }

  @Override
  public String buildDropNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final boolean cascade
  ) {
    return buildDropNamespaceSql(type, name, cascade, null);
  }

  @Override
  public String buildDropNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final boolean cascade,
      final SupportContext context
  ) {
    if (JdbcMetadataModels.NodeType.SCHEMA.equals(type)) {
      return "DROP DATABASE " + quoteIdentifier(name, context);
    }
    return super.buildDropNamespaceSql(type, name, cascade, context);
  }

  @Override
  public String buildCreateTableSql(
      final String qualifiedName,
      final List<JdbcMetadataModels.ColumnDefinition> columns,
      final List<JdbcMetadataModels.ConstraintDefinition> constraints
  ) {
    return buildCreateTableSql(qualifiedName, columns, constraints, null);
  }

  @Override
  public String buildCreateTableSql(
      final String qualifiedName,
      final List<JdbcMetadataModels.ColumnDefinition> columns,
      final List<JdbcMetadataModels.ConstraintDefinition> constraints,
      final SupportContext context
  ) {
    List<JdbcMetadataModels.ColumnDefinition> safeColumns = columns == null ? List.of() : columns;
    if (safeColumns.isEmpty()) {
      throw new IllegalArgumentException("创建数据表时至少需要一个字段");
    }
    List<String> definitions = safeColumns.stream()
        .map(column -> columnDefinition(column, true, context))
        .toList();
    return "CREATE TABLE " + qualifiedName + " (" + String.join(", ", definitions)
        + ") ENGINE = MergeTree() ORDER BY " + orderByClause(constraints, context);
  }

  @Override
  public String buildAddColumnSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ColumnDefinition column
  ) {
    return buildAddColumnSql(qualifiedTableName, column, null);
  }

  @Override
  public String buildAddColumnSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ColumnDefinition column,
      final SupportContext context
  ) {
    return "ALTER TABLE " + qualifiedTableName + " ADD COLUMN " + columnDefinition(column, true, context);
  }

  @Override
  public List<String> buildAlterColumnSql(
      final String qualifiedTableName,
      final String currentColumnName,
      final JdbcMetadataModels.ColumnDefinition column
  ) {
    return buildAlterColumnSql(qualifiedTableName, currentColumnName, column, null);
  }

  @Override
  public List<String> buildAlterColumnSql(
      final String qualifiedTableName,
      final String currentColumnName,
      final JdbcMetadataModels.ColumnDefinition column,
      final SupportContext context
  ) {
    String currentName = requireName(currentColumnName, "当前字段名称不能为空");
    String targetName = requireName(column.name(), "字段名称不能为空");
    List<String> sql = new ArrayList<>();
    if (!currentName.equals(targetName)) {
      sql.add("ALTER TABLE " + qualifiedTableName + " RENAME COLUMN " + quoteIdentifier(currentName, context)
          + " TO " + quoteIdentifier(targetName, context));
    }
    sql.add("ALTER TABLE " + qualifiedTableName + " MODIFY COLUMN " + columnDefinition(column, true, context));
    return List.copyOf(sql);
  }

  @Override
  public String buildAddConstraintSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ConstraintDefinition constraint,
      final SupportContext context
  ) {
    throw new UnsupportedOperationException("ClickHouse 不支持通用约束管理");
  }

  @Override
  public String buildDropConstraintSql(
      final String qualifiedTableName,
      final String constraintName,
      final JdbcMetadataModels.ConstraintType type,
      final SupportContext context
  ) {
    throw new UnsupportedOperationException("ClickHouse 不支持通用约束管理");
  }

  @Override
  public String buildDropTableSql(final String qualifiedName, final boolean cascade) {
    return "DROP TABLE " + qualifiedName;
  }

  @Override
  public String buildDropViewSql(final String qualifiedName, final boolean cascade) {
    return "DROP VIEW " + qualifiedName;
  }

  @Override
  public List<JdbcMetadataModels.ConstraintDefinition> loadConstraints(
      final Connection connection,
      final SupportContext context,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    return List.of();
  }

  @Override
  protected String columnDefinition(
      final JdbcMetadataModels.ColumnDefinition column,
      final boolean includeName,
      final SupportContext context
  ) {
    StringBuilder builder = new StringBuilder();
    if (includeName) {
      builder.append(quoteIdentifier(requireName(column.name(), "字段名称不能为空"), context)).append(' ');
    }
    builder.append(nullableType(column));
    if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
      builder.append(" DEFAULT ").append(column.defaultValue().trim());
    }
    return builder.toString();
  }

  private String nullableType(final JdbcMetadataModels.ColumnDefinition column) {
    String typeSql = columnTypeSql(column);
    if (Boolean.TRUE.equals(column.nullable()) && !typeSql.startsWith("Nullable(")) {
      return "Nullable(" + typeSql + ")";
    }
    if (Boolean.FALSE.equals(column.nullable()) && typeSql.startsWith("Nullable(") && typeSql.endsWith(")")) {
      return typeSql.substring("Nullable(".length(), typeSql.length() - 1);
    }
    return typeSql;
  }

  private String orderByClause(
      final List<JdbcMetadataModels.ConstraintDefinition> constraints,
      final SupportContext context
  ) {
    if (constraints == null) {
      return "tuple()";
    }
    return constraints.stream()
        .filter(constraint -> JdbcMetadataModels.ConstraintType.PRIMARY_KEY.equals(constraint.type()))
        .map(JdbcMetadataModels.ConstraintDefinition::columns)
        .filter(columns -> columns != null && !columns.isEmpty())
        .findFirst()
        .map(columns -> "(" + columns.stream()
            .map(name -> quoteIdentifier(name, context))
            .reduce((left, right) -> left + ", " + right)
            .orElse("tuple()") + ")")
        .orElse("tuple()");
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private static String requireName(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  protected static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
