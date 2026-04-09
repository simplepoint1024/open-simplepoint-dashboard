package org.simplepoint.plugin.dna.core.service.dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;

/**
 * JDBC dialect for MySQL-compatible databases.
 */
public class MysqlJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "mysql";
  }

  @Override
  public String name() {
    return "MySQL";
  }

  @Override
  public String description() {
    return "MySQL and MariaDB compatible dialect";
  }

  @Override
  public int order() {
    return 20;
  }

  @Override
  public boolean supports(final SupportContext context) {
    String databaseType = normalize(context.databaseType());
    String driverClassName = normalize(context.driverClassName());
    return databaseType.contains("mysql")
        || databaseType.contains("mariadb")
        || driverClassName.contains("mysql")
        || driverClassName.contains("mariadb");
  }

  @Override
  public JdbcMetadataModels.NodeType catalogNodeType() {
    return JdbcMetadataModels.NodeType.DATABASE;
  }

  @Override
  public boolean supportsNamespaceCreate(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.DATABASE.equals(type);
  }

  @Override
  public boolean supportsNamespaceDrop(final JdbcMetadataModels.NodeType type) {
    return JdbcMetadataModels.NodeType.DATABASE.equals(type);
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
  public List<String> visibleSchemas(
      final List<String> schemas,
      final String catalog,
      final SupportContext context
  ) {
    return List.of();
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
      return List.of("ALTER TABLE " + qualifiedTableName + " CHANGE COLUMN " + quoteIdentifier(currentName, context)
          + " " + columnDefinition(column, true, context));
    }
    return List.of("ALTER TABLE " + qualifiedTableName + " MODIFY COLUMN " + columnDefinition(column, true, context));
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
    return switch (type) {
      case PRIMARY_KEY -> "ALTER TABLE " + qualifiedTableName + " DROP PRIMARY KEY";
      case FOREIGN_KEY -> "ALTER TABLE " + qualifiedTableName + " DROP FOREIGN KEY "
          + quoteIdentifier(constraintName, context);
      case UNIQUE -> "ALTER TABLE " + qualifiedTableName + " DROP INDEX " + quoteIdentifier(constraintName, context);
      case CHECK -> "ALTER TABLE " + qualifiedTableName + " DROP CHECK " + quoteIdentifier(constraintName, context);
    };
  }

  @Override
  protected List<JdbcMetadataModels.ConstraintDefinition> loadCheckConstraints(
      final Connection connection,
      final SupportContext context,
      final String catalog,
      final String schema,
      final String tableName
  ) throws SQLException {
    String databaseName = schema != null && !schema.isBlank() ? schema : catalog;
    if (databaseName == null || databaseName.isBlank()) {
      databaseName = context.currentCatalog();
    }
    if (databaseName == null || databaseName.isBlank()) {
      return List.of();
    }
    String sql = """
        select tc.constraint_name, cc.check_clause
        from information_schema.table_constraints tc
        join information_schema.check_constraints cc
          on tc.constraint_schema = cc.constraint_schema
         and tc.constraint_name = cc.constraint_name
        where tc.constraint_type = 'CHECK'
          and tc.table_schema = ?
          and tc.table_name = ?
        order by tc.constraint_name
        """;
    List<JdbcMetadataModels.ConstraintDefinition> constraints = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, databaseName);
      statement.setString(2, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          constraints.add(new JdbcMetadataModels.ConstraintDefinition(
              resultSet.getString(1),
              JdbcMetadataModels.ConstraintType.CHECK,
              List.of(),
              null,
              resultSet.getString(2)
          ));
        }
      }
    }
    return constraints;
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }
}
