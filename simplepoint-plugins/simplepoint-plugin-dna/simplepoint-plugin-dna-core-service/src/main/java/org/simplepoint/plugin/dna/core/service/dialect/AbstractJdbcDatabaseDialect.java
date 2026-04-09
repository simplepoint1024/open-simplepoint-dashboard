package org.simplepoint.plugin.dna.core.service.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

/**
 * Base JDBC dialect with reusable metadata and DDL helpers.
 */
public abstract class AbstractJdbcDatabaseDialect implements JdbcDatabaseDialect {

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
    throw new UnsupportedOperationException("当前方言不支持创建命名空间类型: " + type);
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
    throw new UnsupportedOperationException("当前方言不支持删除命名空间类型: " + type);
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
    List<String> definitions = new ArrayList<>();
    List<JdbcMetadataModels.ColumnDefinition> safeColumns = columns == null ? List.of() : columns;
    if (safeColumns.isEmpty()) {
      throw new IllegalArgumentException("创建数据表时至少需要一个字段");
    }
    safeColumns.forEach(column -> definitions.add(columnDefinition(column, true, context)));
    if (constraints != null) {
      constraints.stream()
          .map(constraint -> constraintDefinition(constraint, context))
          .forEach(definitions::add);
    }
    return "CREATE TABLE " + qualifiedName + " (" + String.join(", ", definitions) + ")";
  }

  @Override
  public String buildCreateViewSql(final String qualifiedName, final String definitionSql) {
    return "CREATE VIEW " + qualifiedName + " AS " + definitionSql;
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
    List<String> sql = new ArrayList<>();
    String targetName = requireName(column.name(), "字段名称不能为空");
    String currentName = requireName(currentColumnName, "当前字段名称不能为空");
    if (!currentName.equals(targetName)) {
      sql.add("ALTER TABLE " + qualifiedTableName + " RENAME COLUMN " + quoteIdentifier(currentName, context)
          + " TO " + quoteIdentifier(targetName, context));
    }
    sql.add("ALTER TABLE " + qualifiedTableName + " ALTER COLUMN " + quoteIdentifier(targetName, context)
        + " TYPE " + columnTypeSql(column));
    if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
      sql.add("ALTER TABLE " + qualifiedTableName + " ALTER COLUMN " + quoteIdentifier(targetName, context)
          + " SET DEFAULT " + column.defaultValue().trim());
    } else {
      sql.add("ALTER TABLE " + qualifiedTableName + " ALTER COLUMN " + quoteIdentifier(targetName, context)
          + " DROP DEFAULT");
    }
    if (Boolean.TRUE.equals(column.nullable())) {
      sql.add("ALTER TABLE " + qualifiedTableName + " ALTER COLUMN "
          + quoteIdentifier(targetName, context) + " DROP NOT NULL");
    } else {
      sql.add("ALTER TABLE " + qualifiedTableName + " ALTER COLUMN "
          + quoteIdentifier(targetName, context) + " SET NOT NULL");
    }
    return sql;
  }

  @Override
  public String buildAddConstraintSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ConstraintDefinition constraint
  ) {
    return buildAddConstraintSql(qualifiedTableName, constraint, null);
  }

  @Override
  public String buildAddConstraintSql(
      final String qualifiedTableName,
      final JdbcMetadataModels.ConstraintDefinition constraint,
      final SupportContext context
  ) {
    return "ALTER TABLE " + qualifiedTableName + " ADD " + constraintDefinition(constraint, context);
  }

  @Override
  public String buildDropConstraintSql(
      final String qualifiedTableName,
      final String constraintName,
      final JdbcMetadataModels.ConstraintType type
  ) {
    return buildDropConstraintSql(qualifiedTableName, constraintName, type, null);
  }

  @Override
  public String buildDropConstraintSql(
      final String qualifiedTableName,
      final String constraintName,
      final JdbcMetadataModels.ConstraintType type,
      final SupportContext context
  ) {
    return "ALTER TABLE " + qualifiedTableName + " DROP CONSTRAINT " + quoteIdentifier(requireName(
        constraintName,
        "约束名称不能为空"
    ), context);
  }

  @Override
  public List<JdbcMetadataModels.ConstraintDefinition> loadConstraints(
      final Connection connection,
      final SupportContext context,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    String metadataCatalog = metadataCatalog(catalog, context);
    String metadataSchema = metadataSchema(schema, context);
    List<JdbcMetadataModels.ConstraintDefinition> constraints = new ArrayList<>();
    constraints.addAll(loadPrimaryKeys(metaData, metadataCatalog, metadataSchema, tableName));
    constraints.addAll(loadForeignKeys(metaData, metadataCatalog, metadataSchema, tableName));
    constraints.addAll(loadUniqueConstraints(metaData, metadataCatalog, metadataSchema, tableName));
    constraints.addAll(loadCheckConstraints(connection, context, metadataCatalog, metadataSchema, tableName));
    return constraints;
  }

  /**
   * Returns dialect-specific check constraints.
   *
   * @param connection connection
   * @param context support context
   * @param catalog catalog
   * @param schema schema
   * @param tableName table name
   * @return check constraints
   * @throws SQLException on query failure
   */
  protected List<JdbcMetadataModels.ConstraintDefinition> loadCheckConstraints(
      final Connection connection,
      final SupportContext context,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    return List.of();
  }

  /**
   * Builds the column definition SQL.
   *
   * @param column column definition
   * @param includeName whether to include the column name
   * @return SQL fragment
   */
  protected String columnDefinition(
      final JdbcMetadataModels.ColumnDefinition column,
      final boolean includeName
  ) {
    return columnDefinition(column, includeName, null);
  }

  /**
   * Builds the column definition SQL.
   *
   * @param column column definition
   * @param includeName whether to include the column name
   * @param context support context
   * @return SQL fragment
   */
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
    if (Boolean.FALSE.equals(column.nullable())) {
      builder.append(" NOT NULL");
    }
    if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
      builder.append(" DEFAULT ").append(column.defaultValue().trim());
    }
    if (Boolean.TRUE.equals(column.autoIncrement())) {
      builder.append(" GENERATED BY DEFAULT AS IDENTITY");
    }
    return builder.toString();
  }

  /**
   * Builds the SQL type fragment for a column.
   *
   * @param column column definition
   * @return SQL fragment
   */
  protected String columnTypeSql(final JdbcMetadataModels.ColumnDefinition column) {
    String typeName = requireName(column.typeName(), "字段类型不能为空");
    Integer size = column.size();
    Integer scale = column.scale();
    if (size != null && size > 0) {
      if (scale != null && scale >= 0) {
        return typeName + "(" + size + "," + scale + ")";
      }
      return typeName + "(" + size + ")";
    }
    return typeName;
  }

  /**
   * Builds a constraint definition fragment.
   *
   * @param constraint constraint definition
   * @return SQL fragment
   */
  protected String constraintDefinition(final JdbcMetadataModels.ConstraintDefinition constraint) {
    return constraintDefinition(constraint, null);
  }

  /**
   * Builds a constraint definition fragment.
   *
   * @param constraint constraint definition
   * @param context support context
   * @return SQL fragment
   */
  protected String constraintDefinition(
      final JdbcMetadataModels.ConstraintDefinition constraint,
      final SupportContext context
  ) {
    String namePrefix = constraint.name() == null || constraint.name().isBlank()
        ? ""
        : "CONSTRAINT " + quoteIdentifier(constraint.name().trim(), context) + ' ';
    return switch (constraint.type()) {
      case PRIMARY_KEY -> namePrefix + "PRIMARY KEY (" + joinIdentifiers(constraint.columns(), context) + ")";
      case UNIQUE -> namePrefix + "UNIQUE (" + joinIdentifiers(constraint.columns(), context) + ")";
      case CHECK -> namePrefix + "CHECK (" + requireName(constraint.checkExpression(), "CHECK 约束表达式不能为空") + ")";
      case FOREIGN_KEY -> namePrefix + "FOREIGN KEY (" + joinIdentifiers(constraint.columns(), context) + ") REFERENCES "
          + qualifyReference(constraint.reference(), context) + " (" + joinIdentifiers(requireColumns(
          constraint.reference() == null ? null : constraint.reference().columns()
      ), context) + ")";
    };
  }

  /**
   * Builds a qualified reference table name.
   *
   * @param reference reference target
   * @return qualified reference name
   */
  protected String qualifyReference(final JdbcMetadataModels.ConstraintReference reference) {
    return qualifyReference(reference, null);
  }

  /**
   * Builds a qualified reference table name.
   *
   * @param reference reference target
   * @param context support context
   * @return qualified reference name
   */
  protected String qualifyReference(
      final JdbcMetadataModels.ConstraintReference reference,
      final SupportContext context
  ) {
    if (reference == null || reference.tablePath() == null || reference.tablePath().isEmpty()) {
      throw new IllegalArgumentException("外键引用表不能为空");
    }
    String catalog = null;
    String schema = null;
    String table = null;
    for (JdbcMetadataModels.PathSegment segment : reference.tablePath()) {
      switch (segment.type()) {
        case DATABASE, CATALOG -> catalog = segment.name();
        case SCHEMA -> schema = segment.name();
        case TABLE -> table = segment.name();
        default -> {
        }
      }
    }
    return qualifyName(catalog, schema, requireName(table, "外键引用表不能为空"), context);
  }

  /**
   * Creates a constraint path for table references.
   *
   * @param catalog catalog
   * @param schema schema
   * @param table table
   * @return table path
   */
  protected List<JdbcMetadataModels.PathSegment> tablePath(
      final String catalog,
      final String schema,
      final String table
  ) {
    List<JdbcMetadataModels.PathSegment> path = new ArrayList<>();
    if (catalog != null && !catalog.isBlank()) {
      path.add(new JdbcMetadataModels.PathSegment(catalogNodeType(), catalog));
    }
    if (schema != null && !schema.isBlank()) {
      path.add(new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.SCHEMA, schema));
    }
    path.add(new JdbcMetadataModels.PathSegment(JdbcMetadataModels.NodeType.TABLE, table));
    return List.copyOf(path);
  }

  private List<JdbcMetadataModels.ConstraintDefinition> loadPrimaryKeys(
      final DatabaseMetaData metaData,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    Map<String, TreeMap<Integer, String>> grouped = new LinkedHashMap<>();
    try (ResultSet resultSet = metaData.getPrimaryKeys(catalog, schema, tableName)) {
      while (resultSet.next()) {
        String name = defaultConstraintName(resultSet.getString("PK_NAME"), "pk_" + tableName);
        grouped.computeIfAbsent(name, ignored -> new TreeMap<>())
            .put(resultSet.getInt("KEY_SEQ"), resultSet.getString("COLUMN_NAME"));
      }
    }
    return grouped.entrySet().stream()
        .map(entry -> new JdbcMetadataModels.ConstraintDefinition(
            entry.getKey(),
            JdbcMetadataModels.ConstraintType.PRIMARY_KEY,
            List.copyOf(entry.getValue().values()),
            null,
            null
        ))
        .toList();
  }

  private List<JdbcMetadataModels.ConstraintDefinition> loadForeignKeys(
      final DatabaseMetaData metaData,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    Map<String, ForeignKeySnapshot> grouped = new LinkedHashMap<>();
    try (ResultSet resultSet = metaData.getImportedKeys(catalog, schema, tableName)) {
      while (resultSet.next()) {
        String name = defaultConstraintName(resultSet.getString("FK_NAME"), "fk_" + tableName);
        ForeignKeySnapshot snapshot = grouped.get(name);
        if (snapshot == null) {
          snapshot = new ForeignKeySnapshot(
              resultSet.getString("PKTABLE_CAT"),
              resultSet.getString("PKTABLE_SCHEM"),
              resultSet.getString("PKTABLE_NAME")
          );
          grouped.put(name, snapshot);
        }
        snapshot.localColumns().put(resultSet.getInt("KEY_SEQ"), resultSet.getString("FKCOLUMN_NAME"));
        snapshot.referencedColumns().put(resultSet.getInt("KEY_SEQ"), resultSet.getString("PKCOLUMN_NAME"));
      }
    }
    return grouped.entrySet().stream()
        .map(entry -> new JdbcMetadataModels.ConstraintDefinition(
            entry.getKey(),
            JdbcMetadataModels.ConstraintType.FOREIGN_KEY,
            List.copyOf(entry.getValue().localColumns().values()),
            new JdbcMetadataModels.ConstraintReference(
                tablePath(entry.getValue().catalog(), entry.getValue().schema(), entry.getValue().table()),
                List.copyOf(entry.getValue().referencedColumns().values())
            ),
            null
        ))
        .toList();
  }

  private List<JdbcMetadataModels.ConstraintDefinition> loadUniqueConstraints(
      final DatabaseMetaData metaData,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    Map<String, TreeMap<Short, String>> grouped = new LinkedHashMap<>();
    try (ResultSet resultSet = metaData.getIndexInfo(catalog, schema, tableName, true, false)) {
      while (resultSet.next()) {
        if (resultSet.getBoolean("NON_UNIQUE")) {
          continue;
        }
        String indexName = resultSet.getString("INDEX_NAME");
        String columnName = resultSet.getString("COLUMN_NAME");
        if (indexName == null || columnName == null) {
          continue;
        }
        grouped.computeIfAbsent(indexName, ignored -> new TreeMap<>())
            .put(resultSet.getShort("ORDINAL_POSITION"), columnName);
      }
    }
    return grouped.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .map(entry -> new JdbcMetadataModels.ConstraintDefinition(
            entry.getKey(),
            JdbcMetadataModels.ConstraintType.UNIQUE,
            List.copyOf(entry.getValue().values()),
            null,
            null
        ))
        .toList();
  }

  private String joinIdentifiers(final List<String> names, final SupportContext context) {
    return requireColumns(names).stream()
        .map(name -> quoteIdentifier(name, context))
        .collect(Collectors.joining(", "));
  }

  private static List<String> requireColumns(final List<String> names) {
    if (names == null || names.isEmpty()) {
      throw new IllegalArgumentException("字段列表不能为空");
    }
    return names;
  }

  private static String defaultConstraintName(final String value, final String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  private static String requireName(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private record ForeignKeySnapshot(
      String catalog,
      String schema,
      String table,
      TreeMap<Integer, String> localColumns,
      TreeMap<Integer, String> referencedColumns
  ) {

    private ForeignKeySnapshot(
        final String catalog,
        final String schema,
        final String table
    ) {
      this(catalog, schema, table, new TreeMap<>(), new TreeMap<>());
    }
  }
}
