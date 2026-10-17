package org.simplepoint.plugin.dna.runtime.service.schema;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.sql.SqlDialectFactory;
import org.apache.calcite.sql.SqlDialects;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.CapabilityConstraintOperator;
import org.simplepoint.plugin.dna.runtime.api.dialect.CapabilityConstraintType;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapability;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectOperation;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalJsonValues;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalStorageType;
import org.springframework.stereotype.Component;

/** MySQL 8 executable schema dialect backed by real database integration tests. */
@Component
public class MysqlRuntimeSchemaDialect extends AbstractRuntimeSchemaDialect {

  private static final BigDecimal JSON_MIN_INTEGER = new BigDecimal("-9223372036854775808");
  private static final BigDecimal JSON_MAX_INTEGER = new BigDecimal("18446744073709551615");

  private static final int MYSQL_MAX_ROW_BYTES = 65_535;

  private static final int PORTABLE_ROW_RESERVE_BYTES = 4_096;

  private static final int SYSTEM_VARCHAR_CHARACTERS = 674;

  private static final int PORTABLE_VARCHAR_CHAR_LIMIT =
      (MYSQL_MAX_ROW_BYTES - PORTABLE_ROW_RESERVE_BYTES) / 4
          - SYSTEM_VARCHAR_CHARACTERS;

  /** Creates the MySQL schema dialect. */
  public MysqlRuntimeSchemaDialect(final PhysicalSchemaSnapshotCodec snapshotCodec) {
    super(snapshotCodec);
  }

  @Override
  public void validateRecordJson(final String canonical) {
    for (var number : LogicalJsonValues.numbers(canonical)) {
      boolean nativeInteger = number.stripTrailingZeros().scale() <= 0
          && number.compareTo(JSON_MIN_INTEGER) >= 0
          && number.compareTo(JSON_MAX_INTEGER) <= 0;
      double floating = number.doubleValue();
      if (!nativeInteger && (!Double.isFinite(floating) || BigDecimal.valueOf(floating).compareTo(number) != 0)) {
        throw new DnaRuntimeException(DnaRuntimeErrorCode.VALUE_CONVERSION_FAILED,
            "JSON number cannot be represented exactly by the target database");
      }
    }
  }

  @Override
  public SqlDialectFactory recordSqlDialectFactory() {
    return metadata -> new MysqlRuntimeRecordSqlDialect(SqlDialects.createContext(metadata));
  }

  @Override
  public String dialectId() {
    return "mysql";
  }

  @Override
  public RecordWriteFenceSql recordWriteFenceSql(final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context, final PhysicalSchemaTarget model) {
    String id = coreDialect.quoteIdentifier("id", context);
    return RecordWriteFenceSql.compile(qualifiedTable(coreDialect, context, RecordWriteFenceSchema.target(model)),
        field -> coreDialect.quoteIdentifier(field, context), " ON DUPLICATE KEY UPDATE " + id + " = " + id);
  }

  @Override
  public boolean supports(final DatabaseIdentity database) {
    String product = database.productName().toLowerCase(Locale.ROOT);
    return product.contains("mysql") && !product.contains("mariadb");
  }

  @Override
  int minimumMajorVersion() {
    return 8;
  }

  @Override
  int maxIdentifierLength() {
    return 64;
  }

  @Override
  int maxParameterCount() {
    return 65_535;
  }

  @Override
  void declareAdditionalCapabilities(
      final EnumMap<DialectOperation, DialectCapability> capabilities
  ) {
    capabilities.put(DialectOperation.ATOMIC_DDL, DialectCapability.supported());
    capabilities.put(
        DialectOperation.TRANSACTIONAL_DDL,
        limited(
            CapabilityConstraintType.DDL_TRANSACTION_MODE,
            CapabilityConstraintOperator.EQUALS,
            "IMPLICIT_COMMIT"
        )
    );
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
  void validateSchema(final PhysicalSchemaDefinition schema) {
    int totalVarcharCharacters = schema.fields().stream()
        .filter(field -> field.type() == LogicalFieldType.STRING)
        .mapToInt(LogicalFieldDefinition::length)
        .sum();
    if (totalVarcharCharacters > PORTABLE_VARCHAR_CHAR_LIMIT) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_INVALID,
          "MySQL portable VARCHAR capacity is exceeded for one table"
      );
    }
  }

  @Override
  void validateDefaultSupport(final LogicalFieldDefinition field) {
    if (field.defaultValue() != null
        && field.defaultValue().kind() != LogicalValueKind.NULL
        && Set.of(LogicalFieldType.TEXT, LogicalFieldType.JSON, LogicalFieldType.BINARY)
        .contains(field.type())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_OPERATION_UNSUPPORTED,
          "MySQL portable publication does not support defaults for " + field.type()
      );
    }
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
    String database = normalize(target.metadataTarget().catalogName());
    if (database == null) {
      database = normalize(target.metadataTarget().schemaName());
    }
    return coreDialect.qualifyName(
        database,
        null,
        target.metadataTarget().tableName(),
        context
    );
  }

  @Override
  String nativeType(final PhysicalColumnSnapshot column) {
    return switch (column.storageType()) {
      case STRING -> "VARCHAR(" + column.length() + ")";
      case TEXT -> "LONGTEXT";
      case BOOLEAN -> "BOOLEAN";
      case INT32 -> "INT";
      case INT64 -> "BIGINT";
      case DECIMAL -> "DECIMAL(" + column.precision() + ',' + column.scale() + ')';
      case DATE -> "DATE";
      case TIME -> "TIME(6)";
      case DATETIME -> "DATETIME(6)";
      case UUID -> "CHAR(36)";
      case JSON -> "JSON";
      case BINARY -> "LONGBLOB";
    };
  }

  @Override
  PhysicalStorageType normalizeStorageType(final String rawTypeName, final int jdbcType) {
    String typeName = rawTypeName == null ? "" : rawTypeName.toLowerCase(Locale.ROOT);
    if (typeName.contains("json")) {
      return PhysicalStorageType.JSON;
    }
    if (typeName.contains("longtext") || typeName.equals("text")
        || typeName.contains("mediumtext")) {
      return PhysicalStorageType.TEXT;
    }
    if (typeName.contains("blob") || typeName.contains("binary")) {
      return PhysicalStorageType.BINARY;
    }
    if (typeName.startsWith("bigint")) {
      return PhysicalStorageType.INT64;
    }
    if (typeName.startsWith("int") || typeName.startsWith("integer")) {
      return PhysicalStorageType.INT32;
    }
    if (typeName.startsWith("decimal") || typeName.startsWith("numeric")) {
      return PhysicalStorageType.DECIMAL;
    }
    if (typeName.startsWith("datetime") || typeName.startsWith("timestamp")) {
      return PhysicalStorageType.DATETIME;
    }
    if (typeName.startsWith("date")) {
      return PhysicalStorageType.DATE;
    }
    if (typeName.startsWith("time")) {
      return PhysicalStorageType.TIME;
    }
    if (typeName.startsWith("bool") || typeName.startsWith("tinyint(1)")
        || jdbcType == Types.BOOLEAN || jdbcType == Types.BIT) {
      return PhysicalStorageType.BOOLEAN;
    }
    if (typeName.contains("char") || jdbcType == Types.VARCHAR || jdbcType == Types.CHAR) {
      return PhysicalStorageType.STRING;
    }
    throw new DnaRuntimeException(
        DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
        "Unsupported MySQL physical column type: " + rawTypeName
    );
  }

  @Override
  String buildTableCommentSql(final String qualifiedTable, final String comment) {
    return "ALTER TABLE " + qualifiedTable + " COMMENT = " + quoteLiteral(comment);
  }

  @Override
  String buildColumnCommentSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final ColumnSpec column
  ) {
    return "ALTER TABLE " + qualifiedTable + " MODIFY COLUMN "
        + columnDefinition(coreDialect, context, column)
        + " COMMENT " + quoteLiteral(column.snapshot().comment());
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
    return "ALTER TABLE " + qualifiedTable + " MODIFY COLUMN "
        + columnDefinition(coreDialect, context, column)
        + " COMMENT " + quoteLiteral(column.snapshot().comment());
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
    return "DROP INDEX " + coreDialect.quoteIdentifier(indexName, context)
        + " ON " + qualifiedTable;
  }
}
