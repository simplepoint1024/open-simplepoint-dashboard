package org.simplepoint.plugin.dna.core.service.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.spi.JdbcTypeMapping;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.service.dialect.type.HiveJdbcTypeMapping;

/**
 * JDBC dialect for Apache Hive (HiveServer2 / Beeline).
 *
 * <p>Hive uses a flat namespace with databases (reported as schemas) and tables.
 * Catalogs are usually not meaningful (Hive reports "hive" as the single catalog).
 * Identifier quoting uses backticks ({@code `}). DDL is limited compared to RDBMS:
 * no constraints, limited ALTER COLUMN support.</p>
 */
public class HiveJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "hive";
  }

  @Override
  public String name() {
    return "Hive";
  }

  @Override
  public String description() {
    return "Apache Hive (HiveServer2) dialect";
  }

  @Override
  public int order() {
    return 15;
  }

  @Override
  public JdbcTypeMapping typeMapping() {
    return HiveJdbcTypeMapping.INSTANCE;
  }

  @Override
  public boolean supports(final SupportContext context) {
    String databaseType = normalize(context.databaseType());
    String driverClassName = normalize(context.driverClassName());
    String productName = normalize(context.productName());
    return databaseType.contains("hive")
        || driverClassName.contains("hive")
        || productName.contains("hive");
  }

  @Override
  public List<String> visibleCatalogs(final List<String> catalogs, final SupportContext context) {
    // Hive reports a single "hive" catalog; hide it so the tree shows databases directly
    return List.of();
  }

  @Override
  public String metadataCatalog(final String catalog, final SupportContext context) {
    // Hive metadata API usually ignores catalog; pass null to avoid filtering
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
  public String resolveIdentifierQuoteString(final SupportContext context) {
    // Hive uses backtick for identifier quoting
    return "`";
  }

  @Override
  public String qualifyName(
      final String catalog,
      final String schema,
      final String objectName,
      final SupportContext context
  ) {
    // Hive: database.table (catalog is ignored)
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
      return "DROP DATABASE " + quoteIdentifier(name, context) + (cascade ? " CASCADE" : "");
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
          "ALTER TABLE " + qualifiedTableName + " CHANGE "
              + quoteIdentifier(currentName, context) + " " + columnDefinition(column, true, context)
      );
    }
    return List.of(
        "ALTER TABLE " + qualifiedTableName + " CHANGE "
            + quoteIdentifier(currentName, context) + " " + columnDefinition(column, true, context)
    );
  }

  @Override
  public String buildAddConstraintSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ConstraintDefinition constraint,
      final SupportContext context
  ) {
    throw new UnsupportedOperationException("Hive 不支持约束管理");
  }

  @Override
  public String buildDropConstraintSql(
      final String qualifiedTableName,
      final String constraintName,
      final JdbcMetadataModels.ConstraintType type,
      final SupportContext context
  ) {
    throw new UnsupportedOperationException("Hive 不支持约束管理");
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
