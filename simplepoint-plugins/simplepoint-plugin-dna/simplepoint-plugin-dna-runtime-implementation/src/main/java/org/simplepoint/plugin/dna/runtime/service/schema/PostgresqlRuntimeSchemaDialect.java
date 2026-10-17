package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import org.apache.calcite.sql.SqlDialectFactory;
import org.apache.calcite.sql.SqlDialects;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapability;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectOperation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalStorageType;
import org.springframework.stereotype.Component;

/** PostgreSQL 13+ executable schema dialect backed by real database integration tests. */
@Component
public class PostgresqlRuntimeSchemaDialect extends AbstractRuntimeSchemaDialect {

  @Override
  public SqlDialectFactory recordSqlDialectFactory() {
    return metadata -> new PostgresqlRuntimeRecordSqlDialect(SqlDialects.createContext(metadata));
  }

  /** Creates the PostgreSQL schema dialect. */
  public PostgresqlRuntimeSchemaDialect(final PhysicalSchemaSnapshotCodec snapshotCodec) {
    super(snapshotCodec);
  }

  @Override
  public String dialectId() {
    return "postgresql";
  }

  @Override
  public RecordWriteFenceSql recordWriteFenceSql(final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context, final PhysicalSchemaTarget model) {
    return RecordWriteFenceSql.compile(qualifiedTable(coreDialect, context, RecordWriteFenceSchema.target(model)),
        field -> coreDialect.quoteIdentifier(field, context),
        " ON CONFLICT (" + coreDialect.quoteIdentifier("id", context) + ") DO NOTHING");
  }

  @Override
  public void prepareRecordTransaction(final Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL TIME ZONE 'UTC'");
    }
  }

  @Override
  public void prepareRecordQueryConnection(final Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET TIME ZONE 'UTC'");
    }
  }

  @Override
  public void resetRecordQueryConnection(final Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("RESET TIME ZONE");
    }
  }

  @Override
  public boolean supports(final DatabaseIdentity database) {
    return database.productName().toLowerCase(Locale.ROOT).contains("postgresql");
  }

  @Override
  int minimumMajorVersion() {
    return 13;
  }

  @Override
  int maxIdentifierLength() {
    return 63;
  }

  @Override
  int maxParameterCount() {
    return 65_535;
  }

  @Override
  void declareAdditionalCapabilities(
      final EnumMap<DialectOperation, DialectCapability> capabilities
  ) {
    capabilities.put(DialectOperation.TRANSACTIONAL_DDL, DialectCapability.supported());
    capabilities.put(DialectOperation.ATOMIC_DDL, DialectCapability.supported());
    capabilities.put(DialectOperation.DML_RETURNING, DialectCapability.supported());
    capabilities.put(DialectOperation.DML_GENERATED_KEYS, DialectCapability.supported());
    capabilities.put(DialectOperation.ADD_COLUMN, DialectCapability.supported());
    capabilities.put(DialectOperation.DROP_COLUMN, DialectCapability.supported());
    capabilities.put(DialectOperation.RENAME_COLUMN, DialectCapability.supported());
    capabilities.put(DialectOperation.ALTER_COLUMN_TYPE, DialectCapability.supported());
    capabilities.put(DialectOperation.ALTER_COLUMN_LENGTH, DialectCapability.supported());
    capabilities.put(DialectOperation.ALTER_COLUMN_PRECISION, DialectCapability.supported());
    capabilities.put(DialectOperation.ALTER_COLUMN_NULLABILITY, DialectCapability.supported());
    capabilities.put(DialectOperation.ALTER_COLUMN_DEFAULT, DialectCapability.supported());
    capabilities.put(DialectOperation.DROP_INDEX, DialectCapability.supported());
  }

  @Override
  PhysicalStorageType expectedStorageType(final LogicalFieldDefinition field) {
    return field.type() == LogicalFieldType.UUID
        ? PhysicalStorageType.STRING : super.expectedStorageType(field);
  }

  @Override
  Integer expectedLength(
      final LogicalFieldDefinition field,
      final PhysicalStorageType storageType
  ) {
    return field.type() == LogicalFieldType.UUID
        ? Integer.valueOf(36) : super.expectedLength(field, storageType);
  }

  @Override
  ColumnSpec adaptSystemColumn(final ColumnSpec column) {
    if (column.snapshot().storageType() != PhysicalStorageType.UUID) {
      return column;
    }
    return new ColumnSpec(
        new PhysicalColumnSnapshot(
            column.snapshot().name(),
            PhysicalStorageType.STRING,
            36,
            null,
            null,
            column.snapshot().nullable(),
            column.snapshot().defaultValue(),
            column.snapshot().comment()
        ),
        column.defaultSql()
    );
  }

  @Override
  String qualifiedTable(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target
  ) {
    return coreDialect.qualifyName(
        null,
        normalize(target.metadataTarget().schemaName()),
        target.metadataTarget().tableName(),
        context
    );
  }

  @Override
  String nativeType(final PhysicalColumnSnapshot column) {
    return switch (column.storageType()) {
      case STRING -> "VARCHAR(" + column.length() + ")";
      case TEXT -> "TEXT";
      case BOOLEAN -> "BOOLEAN";
      case INT32 -> "INTEGER";
      case INT64 -> "BIGINT";
      case DECIMAL -> "NUMERIC(" + column.precision() + ',' + column.scale() + ')';
      case DATE -> "DATE";
      case TIME -> "TIME(6)";
      case DATETIME -> "TIMESTAMP(6) WITH TIME ZONE";
      case UUID -> "UUID";
      case JSON -> "JSONB";
      case BINARY -> "BYTEA";
    };
  }

  @Override
  String renderDefault(final LogicalFieldDefinition field) {
    if (field.defaultValue() == null || field.defaultValue().kind() == LogicalValueKind.NULL) {
      return null;
    }
    if (field.defaultValue().kind() == LogicalValueKind.JSON) {
      return quoteLiteral(field.defaultValue().canonicalValue()) + "::jsonb";
    }
    if (field.defaultValue().kind() == LogicalValueKind.BINARY) {
      byte[] bytes = java.util.Base64.getDecoder().decode(field.defaultValue().canonicalValue());
      return "decode(" + quoteLiteral(HexFormat.of().formatHex(bytes)) + ", 'hex')";
    }
    return super.renderDefault(field);
  }

  @Override
  PhysicalStorageType normalizeStorageType(final String rawTypeName, final int jdbcType) {
    String typeName = rawTypeName == null ? "" : rawTypeName.toLowerCase(Locale.ROOT);
    if (typeName.equals("uuid")) {
      return PhysicalStorageType.UUID;
    }
    if (typeName.equals("json") || typeName.equals("jsonb")) {
      return PhysicalStorageType.JSON;
    }
    if (typeName.equals("bytea") || jdbcType == Types.BINARY || jdbcType == Types.VARBINARY) {
      return PhysicalStorageType.BINARY;
    }
    if (typeName.contains("timestamp")) {
      return PhysicalStorageType.DATETIME;
    }
    if (typeName.startsWith("time")) {
      return PhysicalStorageType.TIME;
    }
    if (typeName.equals("date")) {
      return PhysicalStorageType.DATE;
    }
    if (typeName.equals("bool") || typeName.equals("boolean")) {
      return PhysicalStorageType.BOOLEAN;
    }
    if (typeName.equals("int8") || typeName.equals("bigint")) {
      return PhysicalStorageType.INT64;
    }
    if (typeName.equals("int4") || typeName.equals("integer")) {
      return PhysicalStorageType.INT32;
    }
    if (typeName.startsWith("numeric") || typeName.startsWith("decimal")) {
      return PhysicalStorageType.DECIMAL;
    }
    if (typeName.equals("text")) {
      return PhysicalStorageType.TEXT;
    }
    if (typeName.contains("char") || jdbcType == Types.VARCHAR || jdbcType == Types.CHAR) {
      return PhysicalStorageType.STRING;
    }
    throw new org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException(
        org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
        "Unsupported PostgreSQL physical column type: " + rawTypeName
    );
  }

  @Override
  String buildTableCommentSql(final String qualifiedTable, final String comment) {
    return "COMMENT ON TABLE " + qualifiedTable + " IS " + quoteLiteral(comment);
  }

  @Override
  String buildColumnCommentSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final ColumnSpec column
  ) {
    return "COMMENT ON COLUMN " + qualifiedTable + '.'
        + coreDialect.quoteIdentifier(column.snapshot().name(), context)
        + " IS " + quoteLiteral(column.snapshot().comment());
  }

  @Override
  String buildAddColumnSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final ColumnSpec column
  ) {
    return "ALTER TABLE " + qualifiedTable + " ADD COLUMN "
        + columnDefinition(coreDialect, context, column);
  }

  @Override
  String buildRenameColumnSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final String oldName,
      final String newName
  ) {
    return "ALTER TABLE " + qualifiedTable + " RENAME COLUMN "
        + coreDialect.quoteIdentifier(oldName, context) + " TO "
        + coreDialect.quoteIdentifier(newName, context);
  }

  @Override
  String buildAlterColumnSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final ColumnSpec column
  ) {
    String name = coreDialect.quoteIdentifier(column.snapshot().name(), context);
    String nullable = column.snapshot().nullable() ? " DROP NOT NULL" : " SET NOT NULL";
    String defaultClause = column.defaultSql() == null
        ? " DROP DEFAULT" : " SET DEFAULT " + column.defaultSql();
    return "ALTER TABLE " + qualifiedTable
        + " ALTER COLUMN " + name + " TYPE " + nativeType(column.snapshot())
        + " USING " + name + "::" + nativeType(column.snapshot())
        + ", ALTER COLUMN " + name + nullable
        + ", ALTER COLUMN " + name + defaultClause;
  }

  @Override
  String buildDropColumnSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final String columnName
  ) {
    return "ALTER TABLE " + qualifiedTable + " DROP COLUMN "
        + coreDialect.quoteIdentifier(columnName, context);
  }

  @Override
  String buildDropIndexSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target,
      final String qualifiedTable,
      final String indexName
  ) {
    String schema = normalize(target.metadataTarget().schemaName());
    return "DROP INDEX " + coreDialect.qualifyName(
        null,
        schema,
        indexName,
        context
    );
  }
}
