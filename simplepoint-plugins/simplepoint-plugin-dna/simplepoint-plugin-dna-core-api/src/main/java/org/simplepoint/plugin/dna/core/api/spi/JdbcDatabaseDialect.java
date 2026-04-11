package org.simplepoint.plugin.dna.core.api.spi;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

/**
 * Standard SPI for adapting metadata browsing, preview SQL, and DDL behavior across JDBC databases.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}. Classpath dialects should register
 * themselves under {@code META-INF/services/} so they can be detected both from project dependencies and from
 * externally loaded dialect jars.</p>
 */
public interface JdbcDatabaseDialect {

  /**
   * Unique dialect code.
   *
   * @return dialect code
   */
  String code();

  /**
   * Display name.
   *
   * @return dialect name
   */
  String name();

  /**
   * Optional description.
   *
   * @return description
   */
  default String description() {
    return null;
  }

  /**
   * Optional version.
   *
   * @return dialect version
   */
  default String version() {
    return null;
  }

  /**
   * Sort order. Lower values are preferred when multiple dialects support the same driver.
   *
   * @return order
   */
  default int order() {
    return 100;
  }

  /**
   * Returns the JDBC type mapping for this dialect.
   *
   * <p>The mapping normalizes vendor-specific native type names to standard
   * {@link java.sql.Types} constants. Override this method to return a vendor-specific
   * mapping that handles proprietary types such as MySQL {@code YEAR}, PostgreSQL
   * {@code JSONB}, or Oracle {@code BINARY_FLOAT}.</p>
   *
   * @return type mapping, never null
   */
  default JdbcTypeMapping typeMapping() {
    return StandardJdbcTypeMapping.INSTANCE;
  }

  /**
   * Returns whether this dialect supports the supplied driver/runtime context.
   *
   * @param context support context
   * @return true when supported
   */
  boolean supports(SupportContext context);

  /**
   * Indicates how catalog nodes should be rendered in the metadata tree.
   *
   * @return catalog node type
   */
  default JdbcMetadataModels.NodeType catalogNodeType() {
    return JdbcMetadataModels.NodeType.CATALOG;
  }

  /**
   * Filters visible catalogs for the current datasource connection.
   *
   * @param catalogs available catalogs
   * @param context support context
   * @return visible catalogs
   */
  default List<String> visibleCatalogs(final List<String> catalogs, final SupportContext context) {
    return catalogs;
  }

  /**
   * Filters visible schemas for the current datasource connection.
   *
   * @param schemas available schemas
   * @param catalog current catalog
   * @param context support context
   * @return visible schemas
   */
  default List<String> visibleSchemas(
      final List<String> schemas,
      final String catalog,
      final SupportContext context
  ) {
    return schemas;
  }

  /**
   * Normalizes the catalog value used with JDBC metadata APIs.
   *
   * @param catalog requested catalog
   * @param context support context
   * @return normalized catalog
   */
  default String metadataCatalog(final String catalog, final SupportContext context) {
    return catalog;
  }

  /**
   * Normalizes the schema value used with JDBC metadata APIs.
   *
   * @param schema requested schema
   * @param context support context
   * @return normalized schema
   */
  default String metadataSchema(final String schema, final SupportContext context) {
    return schema;
  }

  /**
   * Returns whether metadata operations should reconnect to the target catalog/database.
   *
   * @param targetCatalog target catalog/database
   * @param context current support context
   * @return true when a separate connection is required
   */
  default boolean requiresCatalogConnection(
      final String targetCatalog,
      final SupportContext context
  ) {
    return false;
  }

  /**
   * Rewrites the JDBC URL so a new connection targets the supplied catalog/database.
   *
   * @param jdbcUrl source JDBC URL
   * @param targetCatalog target catalog/database
   * @param context current support context
   * @return rewritten JDBC URL
   */
  default String remapJdbcUrlCatalog(
      final String jdbcUrl,
      final String targetCatalog,
      final SupportContext context
  ) {
    return jdbcUrl;
  }

  /**
   * Describes which namespace layers the current JDBC metadata implementation supports.
   *
   * @param supportsCatalogsInTableDefinitions whether catalog arguments participate in table metadata APIs
   * @param supportsSchemasInTableDefinitions whether schema arguments participate in table metadata APIs
   */
  record MetadataNamespaceSupport(
      boolean supportsCatalogsInTableDefinitions,
      boolean supportsSchemasInTableDefinitions
  ) {
  }

  /**
   * Generic metadata column definition used by dialect-driven metadata loading.
   *
   * @param name column label
   * @param typeName JDBC type name
   * @param jdbcType JDBC type code
   */
  record MetadataColumn(
      String name,
      String typeName,
      int jdbcType
  ) {
  }

  /**
   * Generic tabular metadata payload used across catalog, schema, table, and column discovery.
   *
   * @param columns result columns
   * @param rows result rows
   */
  record MetadataResult(
      List<MetadataColumn> columns,
      List<List<Object>> rows
  ) {

    /**
     * Creates an immutable metadata result while preserving nullable cell values.
     *
     * @param columns result columns
     * @param rows result rows
     */
    public MetadataResult {
      columns = columns == null ? List.of() : List.copyOf(columns);
      rows = rows == null ? List.of() : rows.stream()
          .map(row -> row == null ? List.<Object>of() : Collections.unmodifiableList(new ArrayList<>(row)))
          .toList();
    }
  }

  /**
   * Resolves namespace layering support for the current JDBC metadata implementation.
   *
   * @param metaData JDBC metadata
   * @param context support context
   * @return namespace layering support
   * @throws SQLException on metadata failure
   */
  default MetadataNamespaceSupport resolveMetadataNamespaceSupport(
      final DatabaseMetaData metaData,
      final SupportContext context
  ) throws SQLException {
    return resolveMetadataNamespaceSupport(null, metaData, context);
  }

  /**
   * Resolves namespace layering support for the current JDBC metadata implementation.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @return namespace layering support
   * @throws SQLException on metadata failure
   */
  MetadataNamespaceSupport resolveMetadataNamespaceSupport(
      Connection connection,
      DatabaseMetaData metaData,
      SupportContext context
  ) throws SQLException;

  /**
   * Loads catalog metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @return catalog metadata
   * @throws SQLException on metadata failure
   */
  default MetadataResult loadCatalogs(
      final Connection connection,
      final DatabaseMetaData metaData,
      final SupportContext context
  ) throws SQLException {
    return loadCatalogs(connection, metaData, context, null);
  }

  /**
   * Loads catalog metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @param catalogPattern optional catalog pattern
   * @return catalog metadata
   * @throws SQLException on metadata failure
   */
  MetadataResult loadCatalogs(
      Connection connection,
      DatabaseMetaData metaData,
      SupportContext context,
      String catalogPattern
  ) throws SQLException;

  /**
   * Loads schema metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @return schema metadata
   * @throws SQLException on metadata failure
   */
  default MetadataResult loadSchemas(
      final Connection connection,
      final DatabaseMetaData metaData,
      final SupportContext context
  ) throws SQLException {
    return loadSchemas(connection, metaData, context, null, null);
  }

  /**
   * Loads schema metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @param catalogPattern optional catalog pattern
   * @return schema metadata
   * @throws SQLException on metadata failure
   */
  default MetadataResult loadSchemas(
      final Connection connection,
      final DatabaseMetaData metaData,
      final SupportContext context,
      final String catalogPattern
  ) throws SQLException {
    return loadSchemas(connection, metaData, context, catalogPattern, null);
  }

  /**
   * Loads schema metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @return schema metadata
   * @throws SQLException on metadata failure
   */
  MetadataResult loadSchemas(
      Connection connection,
      DatabaseMetaData metaData,
      SupportContext context,
      String catalogPattern,
      String schemaPattern
  ) throws SQLException;

  /**
   * Loads supported table types.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @return table-type metadata
   * @throws SQLException on metadata failure
   */
  MetadataResult loadTableTypes(
      Connection connection,
      DatabaseMetaData metaData,
      SupportContext context
  ) throws SQLException;

  /**
   * Loads table metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @return table metadata
   * @throws SQLException on metadata failure
   */
  default MetadataResult loadTables(
      final Connection connection,
      final DatabaseMetaData metaData,
      final SupportContext context
  ) throws SQLException {
    return loadTables(connection, metaData, context, null, null, null, null);
  }

  /**
   * Loads table metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param types optional table types
   * @return table metadata
   * @throws SQLException on metadata failure
   */
  MetadataResult loadTables(
      Connection connection,
      DatabaseMetaData metaData,
      SupportContext context,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      List<String> types
  ) throws SQLException;

  /**
   * Loads column metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @return column metadata
   * @throws SQLException on metadata failure
   */
  default MetadataResult loadColumns(
      final Connection connection,
      final DatabaseMetaData metaData,
      final SupportContext context,
      final String catalogPattern,
      final String schemaPattern,
      final String tablePattern
  ) throws SQLException {
    return loadColumns(connection, metaData, context, catalogPattern, schemaPattern, tablePattern, null);
  }

  /**
   * Loads column metadata.
   *
   * @param connection current connection
   * @param metaData JDBC metadata
   * @param context support context
   * @param catalogPattern optional catalog pattern
   * @param schemaPattern optional schema pattern
   * @param tablePattern optional table pattern
   * @param columnPattern optional column pattern
   * @return column metadata
   * @throws SQLException on metadata failure
   */
  MetadataResult loadColumns(
      Connection connection,
      DatabaseMetaData metaData,
      SupportContext context,
      String catalogPattern,
      String schemaPattern,
      String tablePattern,
      String columnPattern
  ) throws SQLException;

  /**
   * Returns whether the dialect supports creating the supplied namespace kind.
   *
   * @param type namespace type
   * @return true when supported
   */
  default boolean supportsNamespaceCreate(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.SCHEMA.equals(type);
  }

  /**
   * Returns whether the dialect supports dropping the supplied namespace kind.
   *
   * @param type namespace type
   * @return true when supported
   */
  default boolean supportsNamespaceDrop(final JdbcMetadataModels.NodeType type) {
    return supportsNamespaceCreate(type);
  }

  /**
   * Returns whether the dialect supports paged table/view data preview.
   *
   * @return true when preview is supported
   */
  default boolean supportsDataPreview() {
    return true;
  }

  /**
   * Returns whether the dialect supports generic table-constraint management actions.
   *
   * @return true when add/drop constraint operations are supported
   */
  default boolean supportsConstraintManagement() {
    return true;
  }

  /**
   * Resolves the identifier quote string from the runtime JDBC metadata context.
   *
   * @param context support context
   * @return quote string, or {@code null} when quoting is unsupported
   */
  default String resolveIdentifierQuoteString(final SupportContext context) {
    if (context == null || context.identifierQuoteString() == null) {
      return "\"";
    }
    String normalized = context.identifierQuoteString().trim();
    return normalized.isEmpty() ? null : normalized;
  }

  /**
   * Quotes an identifier when generating SQL.
   *
   * @param identifier identifier
   * @return quoted identifier
   */
  default String quoteIdentifier(final String identifier) {
    return quoteIdentifier(identifier, null);
  }

  /**
   * Quotes an identifier when generating SQL.
   *
   * @param identifier identifier
   * @param context support context
   * @return quoted identifier
   */
  default String quoteIdentifier(final String identifier, final SupportContext context) {
    String normalized = identifier == null ? "" : identifier.trim();
    String quote = resolveIdentifierQuoteString(context);
    if (quote == null) {
      return normalized;
    }
    if ("[".equals(quote)) {
      return "[" + normalized.replace("]", "]]") + "]";
    }
    return quote + normalized.replace(quote, quote + quote) + quote;
  }

  /**
   * Builds a qualified object name from path parts.
   *
   * @param catalog catalog/database name
   * @param schema schema name
   * @param objectName object name
   * @return qualified name
   */
  default String qualifyName(
      final String catalog,
      final String schema,
      final String objectName
  ) {
    return qualifyName(catalog, schema, objectName, null);
  }

  /**
   * Builds a qualified object name from path parts.
   *
   * @param catalog catalog/database name
   * @param schema schema name
   * @param objectName object name
   * @param context support context
   * @return qualified name
   */
  default String qualifyName(
      final String catalog,
      final String schema,
      final String objectName,
      final SupportContext context
  ) {
    StringBuilder builder = new StringBuilder();
    if (catalog != null && !catalog.isBlank()) {
      builder.append(quoteIdentifier(catalog.trim(), context)).append('.');
    }
    if (schema != null && !schema.isBlank()) {
      builder.append(quoteIdentifier(schema.trim(), context)).append('.');
    }
    builder.append(quoteIdentifier(objectName, context));
    return builder.toString();
  }

  /**
   * Builds the preview SQL used for paged data inspection.
   *
   * @param qualifiedName qualified table/view name
   * @param offset row offset
   * @param size page size
   * @return preview SQL
   */
  default String buildPreviewSql(final String qualifiedName, final long offset, final int size) {
    return "SELECT * FROM " + qualifiedName + " LIMIT " + size + " OFFSET " + offset;
  }

  /**
   * Builds the count SQL used for paged data inspection.
   *
   * @param qualifiedName qualified table/view name
   * @return count SQL
   */
  default String buildPreviewCountSql(final String qualifiedName) {
    return "SELECT COUNT(*) FROM " + qualifiedName;
  }

  /**
   * Builds SQL for creating a namespace using the runtime quote style.
   *
   * @param type namespace type
   * @param name namespace name
   * @param context support context
   * @return create SQL
   */
  default String buildCreateNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final SupportContext context
  ) {
    return buildCreateNamespaceSql(type, name);
  }

  /**
   * Builds SQL for creating a namespace such as a database or schema.
   *
   * @param type namespace type
   * @param name namespace name
   * @return create SQL
   */
  String buildCreateNamespaceSql(JdbcMetadataModels.NodeType type, String name);

  /**
   * Builds SQL for dropping a namespace such as a database or schema.
   *
   * @param type namespace type
   * @param name namespace name
   * @param cascade whether to cascade
   * @return drop SQL
   */
  String buildDropNamespaceSql(JdbcMetadataModels.NodeType type, String name, boolean cascade);

  /**
   * Builds SQL for dropping a namespace using the runtime quote style.
   *
   * @param type namespace type
   * @param name namespace name
   * @param cascade whether to cascade
   * @param context support context
   * @return drop SQL
   */
  default String buildDropNamespaceSql(
      final JdbcMetadataModels.NodeType type,
      final String name,
      final boolean cascade,
      final SupportContext context
  ) {
    return buildDropNamespaceSql(type, name, cascade);
  }

  /**
   * Builds SQL for creating a table.
   *
   * @param qualifiedName qualified table name
   * @param columns column definitions
   * @param constraints constraint definitions
   * @return create SQL
   */
  String buildCreateTableSql(
      String qualifiedName,
      List<JdbcMetadataModels.ColumnDefinition> columns,
      List<JdbcMetadataModels.ConstraintDefinition> constraints
  );

  /**
   * Builds SQL for creating a table using the runtime quote style.
   *
   * @param qualifiedName qualified table name
   * @param columns column definitions
   * @param constraints constraint definitions
   * @param context support context
   * @return create SQL
   */
  default String buildCreateTableSql(
      final String qualifiedName,
      final List<JdbcMetadataModels.ColumnDefinition> columns,
      final List<JdbcMetadataModels.ConstraintDefinition> constraints,
      final SupportContext context
  ) {
    return buildCreateTableSql(qualifiedName, columns, constraints);
  }

  /**
   * Builds SQL for dropping a table.
   *
   * @param qualifiedName qualified table name
   * @param cascade whether to cascade
   * @return drop SQL
   */
  default String buildDropTableSql(final String qualifiedName, final boolean cascade) {
    return "DROP TABLE " + qualifiedName + (cascade ? " CASCADE" : "");
  }

  /**
   * Builds SQL for creating a view.
   *
   * @param qualifiedName qualified view name
   * @param definitionSql view query definition
   * @return create SQL
   */
  String buildCreateViewSql(String qualifiedName, String definitionSql);

  /**
   * Builds SQL for dropping a view.
   *
   * @param qualifiedName qualified view name
   * @param cascade whether to cascade
   * @return drop SQL
   */
  default String buildDropViewSql(final String qualifiedName, final boolean cascade) {
    return "DROP VIEW " + qualifiedName + (cascade ? " CASCADE" : "");
  }

  /**
   * Builds SQL for adding a column.
   *
   * @param qualifiedTableName qualified table name
   * @param column column definition
   * @return alter SQL
   */
  String buildAddColumnSql(String qualifiedTableName, JdbcMetadataModels.ColumnDefinition column);

  /**
   * Builds SQL for adding a column using the runtime quote style.
   *
   * @param qualifiedTableName qualified table name
   * @param column column definition
   * @param context support context
   * @return alter SQL
   */
  default String buildAddColumnSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ColumnDefinition column,
      final SupportContext context
  ) {
    return buildAddColumnSql(qualifiedTableName, column);
  }

  /**
   * Builds SQL statements for altering a column.
   *
   * @param qualifiedTableName qualified table name
   * @param currentColumnName current column name
   * @param column target column definition
   * @return alter SQL statements
   */
  List<String> buildAlterColumnSql(
      String qualifiedTableName,
      String currentColumnName,
      JdbcMetadataModels.ColumnDefinition column
  );

  /**
   * Builds SQL statements for altering a column using the runtime quote style.
   *
   * @param qualifiedTableName qualified table name
   * @param currentColumnName current column name
   * @param column target column definition
   * @param context support context
   * @return alter SQL statements
   */
  default List<String> buildAlterColumnSql(
      final String qualifiedTableName,
      final String currentColumnName,
      final JdbcMetadataModels.ColumnDefinition column,
      final SupportContext context
  ) {
    return buildAlterColumnSql(qualifiedTableName, currentColumnName, column);
  }

  /**
   * Builds SQL for dropping a column.
   *
   * @param qualifiedTableName qualified table name
   * @param columnName column name
   * @return alter SQL
   */
  default String buildDropColumnSql(final String qualifiedTableName, final String columnName) {
    return "ALTER TABLE " + qualifiedTableName + " DROP COLUMN " + quoteIdentifier(columnName);
  }

  /**
   * Builds SQL for dropping a column using the runtime quote style.
   *
   * @param qualifiedTableName qualified table name
   * @param columnName column name
   * @param context support context
   * @return alter SQL
   */
  default String buildDropColumnSql(
      final String qualifiedTableName,
      final String columnName,
      final SupportContext context
  ) {
    return "ALTER TABLE " + qualifiedTableName + " DROP COLUMN " + quoteIdentifier(columnName, context);
  }

  /**
   * Builds SQL for adding a table constraint.
   *
   * @param qualifiedTableName qualified table name
   * @param constraint constraint definition
   * @return alter SQL
   */
  String buildAddConstraintSql(
      String qualifiedTableName,
      JdbcMetadataModels.ConstraintDefinition constraint
  );

  /**
   * Builds SQL for adding a table constraint using the runtime quote style.
   *
   * @param qualifiedTableName qualified table name
   * @param constraint constraint definition
   * @param context support context
   * @return alter SQL
   */
  default String buildAddConstraintSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ConstraintDefinition constraint,
      final SupportContext context
  ) {
    return buildAddConstraintSql(qualifiedTableName, constraint);
  }

  /**
   * Builds SQL for dropping a table constraint.
   *
   * @param qualifiedTableName qualified table name
   * @param constraintName constraint name
   * @param type constraint type
   * @return alter SQL
   */
  String buildDropConstraintSql(
      String qualifiedTableName,
      String constraintName,
      JdbcMetadataModels.ConstraintType type
  );

  /**
   * Builds SQL for dropping a table constraint using the runtime quote style.
   *
   * @param qualifiedTableName qualified table name
   * @param constraintName constraint name
   * @param type constraint type
   * @param context support context
   * @return alter SQL
   */
  default String buildDropConstraintSql(
      final String qualifiedTableName,
      final String constraintName,
      final JdbcMetadataModels.ConstraintType type,
      final SupportContext context
  ) {
    return buildDropConstraintSql(qualifiedTableName, constraintName, type);
  }

  /**
   * Loads table constraints for structure browsing.
   *
   * @param connection current connection
   * @param context support context
   * @param catalog catalog/database name
   * @param schema schema name
   * @param tableName table name
   * @return table constraints
   * @throws SQLException on query failure
   */
  default List<JdbcMetadataModels.ConstraintDefinition> loadConstraints(
      final Connection connection,
      final SupportContext context,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    return List.of();
  }

  /**
   * Describes the runtime context used for dialect matching and behavior decisions.
   *
   * @param databaseType datasource driver database type
   * @param driverClassName driver class name
   * @param productName database product name
   * @param productVersion database product version
   * @param currentCatalog current connection catalog
   * @param currentSchema current connection schema
   * @param identifierQuoteString identifier quote string from JDBC metadata
   * @param attributes extra attributes
   */
  record SupportContext(
      String databaseType,
      String driverClassName,
      String productName,
      String productVersion,
      String currentCatalog,
      String currentSchema,
      String identifierQuoteString,
      Map<String, String> attributes
  ) {
  }
}
