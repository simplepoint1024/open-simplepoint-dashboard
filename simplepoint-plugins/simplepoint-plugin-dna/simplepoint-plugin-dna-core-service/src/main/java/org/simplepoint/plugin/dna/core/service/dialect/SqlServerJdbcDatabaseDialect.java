package org.simplepoint.plugin.dna.core.service.dialect;

import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

/**
 * JDBC dialect for Microsoft SQL Server.
 */
public class SqlServerJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "sqlserver";
  }

  @Override
  public String name() {
    return "SQL Server";
  }

  @Override
  public String description() {
    return "Microsoft SQL Server dialect";
  }

  @Override
  public int order() {
    return 25;
  }

  @Override
  public boolean supports(final SupportContext context) {
    String databaseType = normalize(context.databaseType());
    String driverClassName = normalize(context.driverClassName());
    String productName = normalize(context.productName());
    return databaseType.contains("sqlserver")
        || databaseType.contains("mssql")
        || driverClassName.contains("sqlserver")
        || productName.contains("sql server");
  }

  @Override
  public JdbcMetadataModels.NodeType catalogNodeType() {
    return JdbcMetadataModels.NodeType.DATABASE;
  }

  @Override
  public boolean supportsNamespaceCreate(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.DATABASE.equals(type) || JdbcMetadataModels.NodeType.SCHEMA.equals(type);
  }

  @Override
  public boolean supportsNamespaceDrop(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.DATABASE.equals(type) || JdbcMetadataModels.NodeType.SCHEMA.equals(type);
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
    if (JdbcMetadataModels.NodeType.DATABASE.equals(type)) {
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
    if (JdbcMetadataModels.NodeType.DATABASE.equals(type)) {
      return "DROP DATABASE " + quoteIdentifier(name, context);
    }
    return super.buildDropNamespaceSql(type, name, cascade, context);
  }

  @Override
  public String buildPreviewSql(final String qualifiedName, final long offset, final int size) {
    return "SELECT * FROM " + qualifiedName + " ORDER BY (SELECT 1) OFFSET "
        + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
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
    return "ALTER TABLE " + qualifiedTableName + " ADD " + columnDefinition(column, true, context);
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
      sql.add("EXEC sp_rename N'" + qualifiedTableName + "." + quoteIdentifier(currentName, context)
          + "', N'" + targetName + "', 'COLUMN'");
    }
    sql.add("ALTER TABLE " + qualifiedTableName + " ALTER COLUMN " + alterColumnDefinition(targetName, column, context));
    if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
      sql.add("ALTER TABLE " + qualifiedTableName + " ADD DEFAULT " + column.defaultValue().trim() + " FOR "
          + quoteIdentifier(targetName, context));
    }
    return List.copyOf(sql);
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
  protected String columnDefinition(
      final JdbcMetadataModels.ColumnDefinition column,
      final boolean includeName,
      final SupportContext context
  ) {
    StringBuilder builder = new StringBuilder();
    if (includeName) {
      builder.append(quoteIdentifier(requireName(column.name(), "字段名称不能为空"), context)).append(' ');
    }
    builder.append(columnTypeSql(column));
    if (Boolean.TRUE.equals(column.autoIncrement())) {
      builder.append(" IDENTITY(1,1)");
    }
    if (Boolean.FALSE.equals(column.nullable())) {
      builder.append(" NOT NULL");
    } else if (Boolean.TRUE.equals(column.nullable())) {
      builder.append(" NULL");
    }
    if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
      builder.append(" DEFAULT ").append(column.defaultValue().trim());
    }
    return builder.toString();
  }

  private String alterColumnDefinition(
      final String columnName,
      final JdbcMetadataModels.ColumnDefinition column,
      final SupportContext context
  ) {
    StringBuilder builder = new StringBuilder();
    builder.append(quoteIdentifier(columnName, context)).append(' ').append(columnTypeSql(column));
    if (Boolean.FALSE.equals(column.nullable())) {
      builder.append(" NOT NULL");
    } else {
      builder.append(" NULL");
    }
    return builder.toString();
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
}
