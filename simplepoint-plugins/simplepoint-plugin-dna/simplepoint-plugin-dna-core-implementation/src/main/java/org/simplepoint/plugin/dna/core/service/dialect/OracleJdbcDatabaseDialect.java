package org.simplepoint.plugin.dna.core.service.dialect;

import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.spi.JdbcTypeMapping;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.service.dialect.type.OracleJdbcTypeMapping;

/**
 * JDBC dialect for Oracle databases.
 */
public class OracleJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "oracle";
  }

  @Override
  public String name() {
    return "Oracle";
  }

  @Override
  public String description() {
    return "Oracle Database dialect";
  }

  @Override
  public int order() {
    return 15;
  }

  @Override
  public JdbcTypeMapping typeMapping() {
    return OracleJdbcTypeMapping.INSTANCE;
  }

  @Override
  public boolean supports(final SupportContext context) {
    String databaseType = normalize(context.databaseType());
    String driverClassName = normalize(context.driverClassName());
    String productName = normalize(context.productName());
    return databaseType.contains("oracle")
        || driverClassName.contains("oracle")
        || productName.contains("oracle");
  }

  @Override
  public String metadataCatalog(final String catalog, final SupportContext context) {
    return null;
  }

  @Override
  public boolean supportsNamespaceCreate(final JdbcMetadataModels.NodeType type) {
    return false;
  }

  @Override
  public boolean supportsNamespaceDrop(final JdbcMetadataModels.NodeType type) {
    return false;
  }

  @Override
  public String buildPreviewSql(final String qualifiedName, final long offset, final int size) {
    return "SELECT * FROM " + qualifiedName + " ORDER BY 1 OFFSET "
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
    return "ALTER TABLE " + qualifiedTableName + " ADD (" + columnDefinition(column, true, context) + ")";
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
    sql.add("ALTER TABLE " + qualifiedTableName + " MODIFY (" + columnDefinition(column, true, context) + ")");
    return List.copyOf(sql);
  }

  @Override
  public String buildDropTableSql(final String qualifiedName, final boolean cascade) {
    return "DROP TABLE " + qualifiedName + (cascade ? " CASCADE CONSTRAINTS" : "");
  }

  @Override
  public String buildDropViewSql(final String qualifiedName, final boolean cascade) {
    return "DROP VIEW " + qualifiedName;
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
