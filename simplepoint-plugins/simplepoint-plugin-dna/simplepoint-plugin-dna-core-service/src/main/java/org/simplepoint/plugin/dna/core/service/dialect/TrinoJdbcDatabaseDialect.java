package org.simplepoint.plugin.dna.core.service.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.spi.JdbcTypeMapping;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.service.dialect.type.TrinoJdbcTypeMapping;

/**
 * JDBC dialect for Trino (formerly PrestoSQL) query engine.
 *
 * <p>Trino uses a {@code catalog.schema.table} namespace identical to Presto. Catalogs
 * correspond to connectors. DDL is connector-dependent. Trino's JDBC driver reports
 * product name as "Trino".</p>
 */
public class TrinoJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "trino";
  }

  @Override
  public String name() {
    return "Trino";
  }

  @Override
  public String description() {
    return "Trino (formerly PrestoSQL) distributed SQL query engine dialect";
  }

  @Override
  public int order() {
    return 13;
  }

  @Override
  public JdbcTypeMapping typeMapping() {
    return TrinoJdbcTypeMapping.INSTANCE;
  }

  @Override
  public boolean supports(final SupportContext context) {
    String databaseType = normalize(context.databaseType());
    String driverClassName = normalize(context.driverClassName());
    String productName = normalize(context.productName());
    return databaseType.contains("trino")
        || driverClassName.contains("trino")
        || productName.contains("trino");
  }

  @Override
  public JdbcMetadataModels.NodeType catalogNodeType() {
    return JdbcMetadataModels.NodeType.CATALOG;
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
    StringBuilder builder = new StringBuilder();
    String cat = trimToNull(catalog);
    String sch = trimToNull(schema);
    if (cat != null) {
      builder.append(quoteIdentifier(cat, context)).append('.');
    }
    if (sch != null) {
      builder.append(quoteIdentifier(sch, context)).append('.');
    }
    builder.append(quoteIdentifier(objectName, context));
    return builder.toString();
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
      return "CREATE SCHEMA " + quoteIdentifier(name, context);
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
      return "DROP SCHEMA " + quoteIdentifier(name, context) + (cascade ? " CASCADE" : "");
    }
    return super.buildDropNamespaceSql(type, name, cascade, context);
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
    String currentName = currentColumnName == null ? "" : currentColumnName.trim();
    String targetName = column.name() == null ? "" : column.name().trim();
    if (!currentName.isEmpty() && !currentName.equals(targetName)) {
      return List.of(
          "ALTER TABLE " + qualifiedTableName + " RENAME COLUMN "
              + quoteIdentifier(currentName, context) + " TO " + quoteIdentifier(targetName, context)
      );
    }
    return List.of();
  }

  @Override
  public String buildAddConstraintSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ConstraintDefinition constraint,
      final SupportContext context
  ) {
    throw new UnsupportedOperationException("Trino 不支持约束管理");
  }

  @Override
  public String buildDropConstraintSql(
      final String qualifiedTableName,
      final String constraintName,
      final JdbcMetadataModels.ConstraintType type,
      final SupportContext context
  ) {
    throw new UnsupportedOperationException("Trino 不支持约束管理");
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

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  protected static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
