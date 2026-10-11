package org.simplepoint.plugin.dna.runtime.service.schema;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.dialect.CapabilityConstraintOperator;
import org.simplepoint.plugin.dna.runtime.api.dialect.CapabilityConstraintType;
import org.simplepoint.plugin.dna.runtime.api.dialect.CapabilitySupportLevel;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapability;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityConstraint;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityDescriptor;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectOperation;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectSupportTier;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationOperation;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationRisk;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalIndexSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalStorageType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

/** Shared strict planner and JDBC metadata normalizer for supported Runtime dialects. */
abstract class AbstractRuntimeSchemaDialect implements RuntimeSchemaDialect {

  private static final List<ColumnSpec> SYSTEM_COLUMNS = List.of(
      system("id", PhysicalStorageType.UUID, false, null, "平台记录标识"),
      system("tenant_id", PhysicalStorageType.STRING, false, null, "平台租户标识", 255),
      system(
          "record_version",
          PhysicalStorageType.INT64,
          false,
          "INTEGER:0",
          "记录乐观锁版本"
      ),
      system("created_by", PhysicalStorageType.STRING, false, null, "创建人", 64),
      system(
          "created_org_dept_id",
          PhysicalStorageType.STRING,
          true,
          null,
          "创建组织",
          255
      ),
      system("created_at", PhysicalStorageType.DATETIME, false, null, "创建时间"),
      system("updated_by", PhysicalStorageType.STRING, false, null, "最后更新人", 64),
      system("updated_at", PhysicalStorageType.DATETIME, false, null, "最后更新时间"),
      system("deleted_at", PhysicalStorageType.DATETIME, true, null, "软删除时间")
  );

  private static final Set<DialectOperation> INITIAL_OPERATIONS = Set.of(
      DialectOperation.CREATE_TABLE,
      DialectOperation.ADD_PRIMARY_KEY,
      DialectOperation.CREATE_INDEX,
      DialectOperation.COMMENT_TABLE,
      DialectOperation.COMMENT_COLUMN,
      DialectOperation.TYPE_MAPPING,
      DialectOperation.IDENTIFIER_RULES
  );

  final PhysicalSchemaSnapshotCodec snapshotCodec;

  AbstractRuntimeSchemaDialect(final PhysicalSchemaSnapshotCodec snapshotCodec) {
    this.snapshotCodec = snapshotCodec;
  }

  @Override
  public final InitialSchemaPlan planInitial(
      final Connection connection,
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final DatabaseIdentity database,
      final PhysicalSchemaTarget target,
      final String tableComment,
      final PhysicalSchemaDefinition schema
  ) throws SQLException {
    DialectCapabilityDescriptor descriptor = describe(database);
    INITIAL_OPERATIONS.forEach(descriptor::requireSupported);
    validateVersion(database);
    validateSchema(schema);
    validateIdentifierLimits(connection.getMetaData(), target, schema);
    List<ColumnSpec> columns = compileColumns(schema);
    String qualifiedTable = qualifiedTable(coreDialect, context, target);
    List<PhysicalIndexSnapshot> expectedIndexes = expectedIndexes(target, schema);
    PhysicalTableSnapshot expected = snapshotCodec.snapshot(
        true,
        columns.stream().map(ColumnSpec::snapshot).toList(),
        expectedIndexes,
        normalize(tableComment)
    );
    PhysicalTableSnapshot actual = inspect(connection, coreDialect, context, target);
    validateExistingBase(actual, expected);

    List<PlannedSchemaStep> steps = new ArrayList<>();
    addStep(
        steps,
        SchemaMigrationOperation.CREATE_TABLE,
        "__table__",
        "__table__",
        buildCreateTableSql(coreDialect, context, qualifiedTable, columns)
    );
    for (LogicalIndexDefinition index : schema.indexes()) {
      PhysicalIndexSnapshot expectedIndex = expectedIndexes.stream()
          .filter(value -> value.name().equals(indexName(target, index.code())))
          .findFirst()
          .orElseThrow();
      addStep(
          steps,
          SchemaMigrationOperation.CREATE_INDEX,
          index.code(),
          expectedIndex.name(),
          buildCreateIndexSql(coreDialect, context, qualifiedTable, expectedIndex)
      );
    }
    if (normalize(tableComment) != null) {
      addStep(
          steps,
          SchemaMigrationOperation.COMMENT_TABLE,
          "__table__",
          "__table__",
          buildTableCommentSql(qualifiedTable, tableComment)
      );
    }
    for (ColumnSpec column : columns) {
      if (column.snapshot().comment() != null) {
        addStep(
            steps,
            SchemaMigrationOperation.COMMENT_COLUMN,
            column.snapshot().name(),
            column.snapshot().name(),
            buildColumnCommentSql(
                coreDialect,
                context,
                qualifiedTable,
                column
            )
        );
      }
    }
    return new InitialSchemaPlan(
        dialectId(),
        database,
        actual,
        expected,
        SchemaMigrationRisk.SAFE,
        steps
    );
  }

  @Override
  public final InitialSchemaPlan planEvolution(
      final Connection connection,
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final DatabaseIdentity database,
      final PhysicalSchemaTarget target,
      final String tableComment,
      final PhysicalSchemaDefinition currentSchema,
      final PhysicalSchemaDefinition targetSchema
  ) throws SQLException {
    validateVersion(database);
    validateSchema(targetSchema);
    validateIdentifierLimits(connection.getMetaData(), target, targetSchema);
    String qualifiedTable = qualifiedTable(coreDialect, context, target);
    PhysicalTableSnapshot actual = inspect(connection, coreDialect, context, target);
    PhysicalTableSnapshot current = expectedSnapshot(target, tableComment, currentSchema);
    if (!actual.checksum().equals(current.checksum())) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
          "The live table no longer matches the active model version"
      );
    }
    PhysicalTableSnapshot expected = expectedSnapshot(target, tableComment, targetSchema);
    List<PlannedSchemaStep> steps = new ArrayList<>();
    List<DialectOperation> requiredCapabilities = new ArrayList<>();
    planIndexDrops(
        coreDialect,
        context,
        target,
        current,
        expected,
        qualifiedTable,
        steps,
        requiredCapabilities
    );
    planFieldChanges(
        connection,
        coreDialect,
        context,
        target,
        currentSchema,
        targetSchema,
        qualifiedTable,
        steps,
        requiredCapabilities
    );
    planIndexCreates(
        coreDialect,
        context,
        target,
        current,
        expected,
        qualifiedTable,
        steps,
        requiredCapabilities
    );
    if (!Objects.equals(actual.tableComment(), expected.tableComment())) {
      addEvolutionStep(
          steps,
          requiredCapabilities,
          SchemaMigrationOperation.COMMENT_TABLE,
          DialectOperation.COMMENT_TABLE,
          "__table__",
          "__table__",
          SchemaMigrationRisk.SAFE,
          buildTableCommentSql(qualifiedTable, tableComment)
      );
    }
    SchemaMigrationRisk risk = steps.stream().map(PlannedSchemaStep::risk)
        .max(java.util.Comparator.naturalOrder()).orElse(SchemaMigrationRisk.SAFE);
    DialectCapabilityDescriptor descriptor = describe(database);
    requiredCapabilities.forEach(descriptor::requireSupported);
    return new InitialSchemaPlan(
        dialectId(),
        database,
        actual,
        expected,
        risk,
        steps
    );
  }

  @Override
  public final PhysicalTableSnapshot inspect(
      final Connection connection,
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target
  ) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    String catalog = metadataCatalog(coreDialect, context, target);
    String schema = metadataSchema(coreDialect, context, target);
    String tableName = target.metadataTarget().tableName();
    TableIdentity table = findTable(metadata, catalog, schema, tableName);
    if (table == null) {
      return snapshotCodec.snapshot(false, List.of(), List.of(), null);
    }
    List<PhysicalColumnSnapshot> columns = loadColumns(
        metadata,
        table.catalog(),
        table.schema(),
        table.name()
    );
    List<PhysicalIndexSnapshot> indexes = loadIndexes(
        metadata,
        table.catalog(),
        table.schema(),
        table.name()
    );
    return snapshotCodec.snapshot(true, columns, indexes, table.comment());
  }

  @Override
  public final DialectCapabilityDescriptor describe(final DatabaseIdentity database) {
    validateVersion(database);
    EnumMap<DialectOperation, DialectCapability> capabilities =
        new EnumMap<>(DialectOperation.class);
    for (DialectOperation operation : DialectOperation.values()) {
      capabilities.put(operation, DialectCapability.unsupported());
    }
    capabilities.put(DialectOperation.CREATE_TABLE, DialectCapability.supported());
    capabilities.put(DialectOperation.ADD_PRIMARY_KEY, DialectCapability.supported());
    capabilities.put(DialectOperation.CREATE_INDEX, DialectCapability.supported());
    capabilities.put(DialectOperation.COMMENT_TABLE, DialectCapability.supported());
    capabilities.put(DialectOperation.COMMENT_COLUMN, DialectCapability.supported());
    capabilities.put(
        DialectOperation.IDENTIFIER_RULES,
        limited(
            CapabilityConstraintType.MAX_IDENTIFIER_LENGTH,
            CapabilityConstraintOperator.LESS_THAN_OR_EQUAL,
            maxIdentifierLength()
        )
    );
    capabilities.put(
        DialectOperation.TYPE_MAPPING,
        limited(
            CapabilityConstraintType.SUPPORTED_LOGICAL_TYPES,
            CapabilityConstraintOperator.ONE_OF,
            java.util.Arrays.stream(LogicalFieldType.values())
                .map(Enum::name)
                .sorted()
                .collect(java.util.stream.Collectors.joining(","))
        )
    );
    capabilities.put(
        DialectOperation.DML_BATCH,
        limited(
            CapabilityConstraintType.MAX_PARAMETER_COUNT,
            CapabilityConstraintOperator.LESS_THAN_OR_EQUAL,
            maxParameterCount()
        )
    );
    declareAdditionalCapabilities(capabilities);
    return new DialectCapabilityDescriptor(
        dialectId(),
        database,
        DialectSupportTier.SUPPORTED,
        capabilities
    );
  }

  void declareAdditionalCapabilities(
      final EnumMap<DialectOperation, DialectCapability> capabilities
  ) {
    // Initial publication intentionally declares only evidence-backed capabilities.
  }

  abstract int minimumMajorVersion();

  abstract int maxIdentifierLength();

  abstract int maxParameterCount();

  abstract String qualifiedTable(
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      PhysicalSchemaTarget target
  );

  abstract String nativeType(PhysicalColumnSnapshot column);

  abstract PhysicalStorageType normalizeStorageType(String typeName, int jdbcType);

  abstract String buildTableCommentSql(String qualifiedTable, String comment);

  abstract String buildColumnCommentSql(
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      String qualifiedTable,
      ColumnSpec column
  );

  abstract String buildAddColumnSql(
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      String qualifiedTable,
      ColumnSpec column
  );

  abstract String buildRenameColumnSql(
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      String qualifiedTable,
      String oldName,
      String newName
  );

  abstract String buildAlterColumnSql(
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      String qualifiedTable,
      ColumnSpec column
  );

  abstract String buildDropColumnSql(
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      String qualifiedTable,
      String columnName
  );

  abstract String buildDropIndexSql(
      JdbcDatabaseDialect coreDialect,
      JdbcDatabaseDialect.SupportContext context,
      PhysicalSchemaTarget target,
      String qualifiedTable,
      String indexName
  );

  String buildCreateTableSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final List<ColumnSpec> columns
  ) {
    List<String> definitions = new ArrayList<>();
    columns.forEach(column -> definitions.add(columnDefinition(coreDialect, context, column)));
    definitions.add("PRIMARY KEY (" + coreDialect.quoteIdentifier("id", context) + ")");
    return "CREATE TABLE " + qualifiedTable + " (" + String.join(", ", definitions) + ")";
  }

  String buildCreateIndexSql(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String qualifiedTable,
      final PhysicalIndexSnapshot index
  ) {
    String columns = index.columns().stream()
        .map(value -> coreDialect.quoteIdentifier(value, context))
        .collect(java.util.stream.Collectors.joining(", "));
    return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX "
        + coreDialect.quoteIdentifier(index.name(), context) + " ON "
        + qualifiedTable + " (" + columns + ")";
  }

  final String columnDefinition(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final ColumnSpec column
  ) {
    StringBuilder sql = new StringBuilder()
        .append(coreDialect.quoteIdentifier(column.snapshot().name(), context))
        .append(' ')
        .append(nativeType(column.snapshot()));
    if (!column.snapshot().nullable()) {
      sql.append(" NOT NULL");
    }
    if (column.defaultSql() != null) {
      sql.append(" DEFAULT ").append(column.defaultSql());
    }
    return sql.toString();
  }

  final String quoteLiteral(final String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  final String renderPortableDefault(final LogicalValue value) {
    if (value == null || value.kind() == LogicalValueKind.NULL) {
      return null;
    }
    return switch (value.kind()) {
      case STRING, DATE, TIME, DATETIME, UUID, JSON -> quoteLiteral(value.canonicalValue());
      case BOOLEAN, INTEGER, DECIMAL -> value.canonicalValue();
      case BINARY -> "X'" + HexFormat.of().formatHex(
          Base64.getDecoder().decode(value.canonicalValue())
      ) + "'";
      case NULL -> null;
    };
  }

  String renderDefault(final LogicalFieldDefinition field) {
    return renderPortableDefault(field.defaultValue());
  }

  String canonicalActualDefault(
      final PhysicalStorageType storageType,
      final String rawDefault
  ) {
    String value = normalize(rawDefault);
    if (value == null || "null".equalsIgnoreCase(value)) {
      return null;
    }
    value = stripOuterParentheses(value);
    int castIndex = value.indexOf("::");
    if (castIndex > 0) {
      value = value.substring(0, castIndex);
    }
    value = stripQuotedLiteral(value);
    return switch (storageType) {
      case BOOLEAN -> "BOOLEAN:" + normalizeBoolean(value);
      case INT32, INT64 -> "INTEGER:" + new java.math.BigInteger(value).toString();
      case DECIMAL -> "DECIMAL:" + normalizeDecimal(value);
      case DATE -> "DATE:" + value;
      case TIME -> "TIME:" + value;
      case DATETIME -> "DATETIME:" + normalizeDateTime(value);
      case UUID -> "UUID:" + value.toLowerCase(Locale.ROOT);
      case JSON -> "JSON:" + value;
      case BINARY -> "BINARY:" + value;
      case STRING, TEXT -> "STRING:" + value;
    };
  }

  private List<ColumnSpec> compileColumns(final PhysicalSchemaDefinition schema) {
    List<ColumnSpec> columns = new ArrayList<>(
        SYSTEM_COLUMNS.stream().map(this::adaptSystemColumn).toList()
    );
    for (LogicalFieldDefinition field : schema.fields()) {
      validateDefaultSupport(field);
      PhysicalStorageType storageType = expectedStorageType(field);
      String canonicalDefault = canonicalLogicalDefault(storageType, field.defaultValue());
      PhysicalColumnSnapshot snapshot = new PhysicalColumnSnapshot(
          field.code(),
          storageType,
          expectedLength(field, storageType),
          field.type() == LogicalFieldType.DECIMAL ? field.precision() : null,
          field.type() == LogicalFieldType.DECIMAL ? field.scale() : null,
          field.nullable(),
          canonicalDefault,
          field.displayName()
      );
      columns.add(new ColumnSpec(snapshot, renderDefault(field)));
    }
    return List.copyOf(columns);
  }

  private PhysicalTableSnapshot expectedSnapshot(
      final PhysicalSchemaTarget target,
      final String tableComment,
      final PhysicalSchemaDefinition schema
  ) {
    return snapshotCodec.snapshot(
        true,
        compileColumns(schema).stream().map(ColumnSpec::snapshot).toList(),
        expectedIndexes(target, schema),
        normalize(tableComment)
    );
  }

  private void planFieldChanges(
      final Connection connection,
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target,
      final PhysicalSchemaDefinition currentSchema,
      final PhysicalSchemaDefinition targetSchema,
      final String qualifiedTable,
      final List<PlannedSchemaStep> steps,
      final List<DialectOperation> requiredCapabilities
  ) throws SQLException {
    Map<String, LogicalFieldDefinition> currentById = currentSchema.fields().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            LogicalFieldDefinition::fieldId,
            java.util.function.Function.identity()
        ));
    Map<String, LogicalFieldDefinition> targetById = targetSchema.fields().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            LogicalFieldDefinition::fieldId,
            java.util.function.Function.identity()
        ));
    Map<String, ColumnSpec> targetColumns = compileColumns(targetSchema).stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            column -> column.snapshot().name(),
            java.util.function.Function.identity()
        ));
    for (LogicalFieldDefinition current : currentSchema.fields()) {
      LogicalFieldDefinition targetField = targetById.get(current.fieldId());
      if (targetField == null) {
        addEvolutionStep(
            steps,
            requiredCapabilities,
            SchemaMigrationOperation.DROP_COLUMN,
            DialectOperation.DROP_COLUMN,
            current.code(),
            current.code(),
            SchemaMigrationRisk.DESTRUCTIVE,
            buildDropColumnSql(coreDialect, context, qualifiedTable, current.code())
        );
        continue;
      }
      String liveName = current.code();
      if (!current.code().equals(targetField.code())) {
        addEvolutionStep(
            steps,
            requiredCapabilities,
            SchemaMigrationOperation.RENAME_COLUMN,
            DialectOperation.RENAME_COLUMN,
            current.fieldId(),
            targetField.code(),
            SchemaMigrationRisk.CONDITIONAL,
            buildRenameColumnSql(
                coreDialect,
                context,
                qualifiedTable,
                current.code(),
                targetField.code()
            )
        );
        liveName = targetField.code();
      }
      ColumnSpec targetColumn = targetColumns.get(targetField.code());
      if (requiresStructuralAlter(current, targetField)) {
        SchemaMigrationRisk risk = alterationRisk(current, targetField);
        if (current.nullable() && !targetField.nullable()
            && containsNull(connection, qualifiedTable, coreDialect, context, liveName)) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED,
              "A required-field migration found existing NULL values: " + targetField.code()
          );
        }
        addEvolutionStep(
            steps,
            requiredCapabilities,
            SchemaMigrationOperation.ALTER_COLUMN,
            alterOperation(current, targetField),
            targetField.fieldId(),
            targetField.code(),
            risk,
            buildAlterColumnSql(coreDialect, context, qualifiedTable, targetColumn)
        );
      }
      if (!Objects.equals(current.displayName(), targetField.displayName())) {
        addEvolutionStep(
            steps,
            requiredCapabilities,
            SchemaMigrationOperation.COMMENT_COLUMN,
            DialectOperation.COMMENT_COLUMN,
            targetField.fieldId(),
            targetField.code(),
            SchemaMigrationRisk.SAFE,
            buildColumnCommentSql(coreDialect, context, qualifiedTable, targetColumn)
        );
      }
    }
    for (LogicalFieldDefinition targetField : targetSchema.fields()) {
      if (currentById.containsKey(targetField.fieldId())) {
        continue;
      }
      if (!targetField.nullable() && targetField.defaultValue() == null
          && hasRows(connection, qualifiedTable)) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED,
            "A required field without a default cannot be added to a non-empty table"
        );
      }
      ColumnSpec targetColumn = targetColumns.get(targetField.code());
      SchemaMigrationRisk risk = targetField.nullable() || targetField.defaultValue() != null
          ? SchemaMigrationRisk.SAFE : SchemaMigrationRisk.CONDITIONAL;
      addEvolutionStep(
          steps,
          requiredCapabilities,
          SchemaMigrationOperation.ADD_COLUMN,
          DialectOperation.ADD_COLUMN,
          targetField.fieldId(),
          targetField.code(),
          risk,
          buildAddColumnSql(coreDialect, context, qualifiedTable, targetColumn)
      );
      addEvolutionStep(
          steps,
          requiredCapabilities,
          SchemaMigrationOperation.COMMENT_COLUMN,
          DialectOperation.COMMENT_COLUMN,
          targetField.fieldId(),
          targetField.code(),
          SchemaMigrationRisk.SAFE,
          buildColumnCommentSql(coreDialect, context, qualifiedTable, targetColumn)
      );
    }
  }

  private void planIndexDrops(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target,
      final PhysicalTableSnapshot current,
      final PhysicalTableSnapshot expected,
      final String qualifiedTable,
      final List<PlannedSchemaStep> steps,
      final List<DialectOperation> requiredCapabilities
  ) {
    Map<String, PhysicalIndexSnapshot> expectedByName = expected.indexes().stream()
        .collect(java.util.stream.Collectors.toMap(
            PhysicalIndexSnapshot::name,
            java.util.function.Function.identity()
        ));
    current.indexes().stream().filter(index -> !index.primary()).forEach(index -> {
      if (!index.equals(expectedByName.get(index.name()))) {
        addEvolutionStep(
            steps,
            requiredCapabilities,
            SchemaMigrationOperation.DROP_INDEX,
            DialectOperation.DROP_INDEX,
            index.name(),
            index.name(),
            SchemaMigrationRisk.CONDITIONAL,
            buildDropIndexSql(coreDialect, context, target, qualifiedTable, index.name())
        );
      }
    });
  }

  private void planIndexCreates(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target,
      final PhysicalTableSnapshot current,
      final PhysicalTableSnapshot expected,
      final String qualifiedTable,
      final List<PlannedSchemaStep> steps,
      final List<DialectOperation> requiredCapabilities
  ) {
    Map<String, PhysicalIndexSnapshot> currentByName = current.indexes().stream()
        .collect(java.util.stream.Collectors.toMap(
            PhysicalIndexSnapshot::name,
            java.util.function.Function.identity()
        ));
    expected.indexes().stream().filter(index -> !index.primary()).forEach(index -> {
      if (!index.equals(currentByName.get(index.name()))) {
        addEvolutionStep(
            steps,
            requiredCapabilities,
            SchemaMigrationOperation.CREATE_INDEX,
            DialectOperation.CREATE_INDEX,
            index.name(),
            index.name(),
            SchemaMigrationRisk.SAFE,
            buildCreateIndexSql(coreDialect, context, qualifiedTable, index)
        );
      }
    });
  }

  private void addEvolutionStep(
      final List<PlannedSchemaStep> steps,
      final List<DialectOperation> requiredCapabilities,
      final SchemaMigrationOperation operation,
      final DialectOperation capability,
      final String logicalObject,
      final String satisfactionKey,
      final SchemaMigrationRisk risk,
      final String sql
  ) {
    requiredCapabilities.add(capability);
    int sequence = steps.size() + 1;
    steps.add(new PlannedSchemaStep(
        sequence,
        operation,
        logicalObject,
        satisfactionKey,
        risk,
        snapshotCodec.payload(logicalObject),
        sql,
        snapshotCodec.statementChecksum(operation.name(), sql)
    ));
  }

  private static boolean requiresStructuralAlter(
      final LogicalFieldDefinition current,
      final LogicalFieldDefinition target
  ) {
    return current.type() != target.type()
        || !Objects.equals(current.length(), target.length())
        || !Objects.equals(current.precision(), target.precision())
        || !Objects.equals(current.scale(), target.scale())
        || current.nullable() != target.nullable()
        || !Objects.equals(current.defaultValue(), target.defaultValue());
  }

  private static SchemaMigrationRisk alterationRisk(
      final LogicalFieldDefinition current,
      final LogicalFieldDefinition target
  ) {
    if (current.type() == target.type()) {
      if (current.type() == LogicalFieldType.STRING
          && target.length() != null && current.length() != null
          && target.length() < current.length()) {
        return SchemaMigrationRisk.DESTRUCTIVE;
      }
      if (current.type() == LogicalFieldType.DECIMAL
          && (target.precision() < current.precision() || target.scale() < current.scale())) {
        return SchemaMigrationRisk.DESTRUCTIVE;
      }
      return current.nullable() && !target.nullable()
          ? SchemaMigrationRisk.CONDITIONAL : SchemaMigrationRisk.SAFE;
    }
    if (current.type() == LogicalFieldType.INT32 && target.type() == LogicalFieldType.INT64
        || current.type() == LogicalFieldType.STRING && target.type() == LogicalFieldType.TEXT) {
      return SchemaMigrationRisk.CONDITIONAL;
    }
    return SchemaMigrationRisk.DESTRUCTIVE;
  }

  private static DialectOperation alterOperation(
      final LogicalFieldDefinition current,
      final LogicalFieldDefinition target
  ) {
    if (current.type() != target.type()) {
      return DialectOperation.ALTER_COLUMN_TYPE;
    }
    if (!Objects.equals(current.length(), target.length())) {
      return DialectOperation.ALTER_COLUMN_LENGTH;
    }
    if (!Objects.equals(current.precision(), target.precision())
        || !Objects.equals(current.scale(), target.scale())) {
      return DialectOperation.ALTER_COLUMN_PRECISION;
    }
    if (current.nullable() != target.nullable()) {
      return DialectOperation.ALTER_COLUMN_NULLABILITY;
    }
    return DialectOperation.ALTER_COLUMN_DEFAULT;
  }

  private static boolean hasRows(
      final Connection connection,
      final String qualifiedTable
  ) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT 1 FROM " + qualifiedTable + " LIMIT 1")) {
      return result.next();
    }
  }

  private static boolean containsNull(
      final Connection connection,
      final String qualifiedTable,
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final String column
  ) throws SQLException {
    String sql = "SELECT 1 FROM " + qualifiedTable + " WHERE "
        + coreDialect.quoteIdentifier(column, context) + " IS NULL LIMIT 1";
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      return result.next();
    }
  }

  void validateDefaultSupport(final LogicalFieldDefinition field) {
    // Dialects reject unsupported defaults before any statement is generated.
  }

  void validateSchema(final PhysicalSchemaDefinition schema) {
    // Product-specific portable limits are checked by concrete dialects.
  }

  PhysicalStorageType expectedStorageType(final LogicalFieldDefinition field) {
    return PhysicalStorageType.valueOf(field.type().name());
  }

  Integer expectedLength(
      final LogicalFieldDefinition field,
      final PhysicalStorageType storageType
  ) {
    return field.type() == LogicalFieldType.STRING ? field.length() : null;
  }

  ColumnSpec adaptSystemColumn(final ColumnSpec column) {
    return column;
  }

  private List<PhysicalIndexSnapshot> expectedIndexes(
      final PhysicalSchemaTarget target,
      final PhysicalSchemaDefinition schema
  ) {
    List<PhysicalIndexSnapshot> indexes = new ArrayList<>();
    indexes.add(new PhysicalIndexSnapshot("primary", List.of("id"), true, true));
    schema.indexes().forEach(index -> indexes.add(new PhysicalIndexSnapshot(
        indexName(target, index.code()),
        index.fieldCodes(),
        index.unique(),
        false
    )));
    return List.copyOf(indexes);
  }

  private String indexName(final PhysicalSchemaTarget target, final String code) {
    String seed = target.metadataTarget().tableName() + '|' + code;
    String hash = snapshotCodec.statementChecksum("INDEX", seed);
    return "idx_" + hash.substring(0, Math.min(24, maxIdentifierLength() - 4));
  }

  private void addStep(
      final List<PlannedSchemaStep> steps,
      final SchemaMigrationOperation operation,
      final String logicalObject,
      final String satisfactionKey,
      final String sql
  ) {
    int sequence = steps.size() + 1;
    steps.add(new PlannedSchemaStep(
        sequence,
        operation,
        logicalObject,
        satisfactionKey,
        SchemaMigrationRisk.SAFE,
        snapshotCodec.payload(logicalObject),
        sql,
        snapshotCodec.statementChecksum(operation.name(), sql)
    ));
  }

  private void validateExistingBase(
      final PhysicalTableSnapshot actual,
      final PhysicalTableSnapshot expected
  ) {
    if (!actual.exists()) {
      return;
    }
    if (!baseColumns(actual.columns()).equals(baseColumns(expected.columns()))
        || !primary(actual.indexes()).equals(primary(expected.indexes()))) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_DRIFT_DETECTED,
          "The bound physical table exists with a different base structure"
      );
    }
  }

  private static List<PhysicalIndexSnapshot> primary(
      final List<PhysicalIndexSnapshot> indexes
  ) {
    return indexes.stream().filter(PhysicalIndexSnapshot::primary).toList();
  }

  private static List<PhysicalColumnSnapshot> baseColumns(
      final List<PhysicalColumnSnapshot> columns
  ) {
    return columns.stream().map(column -> new PhysicalColumnSnapshot(
        column.name(),
        column.storageType(),
        column.length(),
        column.precision(),
        column.scale(),
        column.nullable(),
        column.defaultValue(),
        null
    )).toList();
  }

  private void validateVersion(final DatabaseIdentity database) {
    if (!supports(database) || database.majorVersion() < minimumMajorVersion()) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_OPERATION_UNSUPPORTED,
          dialectId() + " " + database.productVersion() + " is not supported for publication"
      );
    }
  }

  private void validateIdentifierLimits(
      final DatabaseMetaData metadata,
      final PhysicalSchemaTarget target,
      final PhysicalSchemaDefinition schema
  ) throws SQLException {
    int metadataLimit = metadata.getMaxTableNameLength();
    int effectiveLimit = metadataLimit > 0
        ? Math.min(metadataLimit, maxIdentifierLength()) : maxIdentifierLength();
    if (target.metadataTarget().tableName().length() > effectiveLimit) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_INVALID,
          "Generated table name exceeds the target database identifier limit"
      );
    }
    if (schema.fields().stream().anyMatch(field -> field.code().length() > effectiveLimit)) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.SCHEMA_INVALID,
          "A field code exceeds the target database identifier limit"
      );
    }
  }

  private List<PhysicalColumnSnapshot> loadColumns(
      final DatabaseMetaData metadata,
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    List<PhysicalColumnSnapshot> columns = new ArrayList<>();
    try (ResultSet resultSet = metadata.getColumns(
        catalog,
        schema,
        escapePattern(metadata, table),
        null
    )) {
      while (resultSet.next()) {
        if (!table.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
          continue;
        }
        String typeName = resultSet.getString("TYPE_NAME");
        int jdbcType = resultSet.getInt("DATA_TYPE");
        PhysicalStorageType storageType = normalizeStorageType(typeName, jdbcType);
        Integer size = nullableInt(resultSet, "COLUMN_SIZE");
        Integer scale = nullableInt(resultSet, "DECIMAL_DIGITS");
        columns.add(new PhysicalColumnSnapshot(
            resultSet.getString("COLUMN_NAME"),
            storageType,
            storageType == PhysicalStorageType.STRING ? size : null,
            storageType == PhysicalStorageType.DECIMAL ? size : null,
            storageType == PhysicalStorageType.DECIMAL ? scale : null,
            resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
            canonicalActualDefault(storageType, resultSet.getString("COLUMN_DEF")),
            resultSet.getString("REMARKS")
        ));
      }
    }
    return columns;
  }

  private List<PhysicalIndexSnapshot> loadIndexes(
      final DatabaseMetaData metadata,
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    List<String> primaryColumns = new ArrayList<>();
    try (ResultSet resultSet = metadata.getPrimaryKeys(catalog, schema, table)) {
      TreeMap<Integer, String> ordered = new TreeMap<>();
      while (resultSet.next()) {
        ordered.put(resultSet.getInt("KEY_SEQ"), resultSet.getString("COLUMN_NAME"));
      }
      primaryColumns.addAll(ordered.values());
    }
    Map<String, IndexAccumulator> secondary = new LinkedHashMap<>();
    try (ResultSet resultSet = metadata.getIndexInfo(catalog, schema, table, false, false)) {
      while (resultSet.next()) {
        String indexName = normalize(resultSet.getString("INDEX_NAME"));
        String columnName = normalize(resultSet.getString("COLUMN_NAME"));
        if (indexName == null || columnName == null
            || resultSet.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
          continue;
        }
        IndexAccumulator accumulator = secondary.computeIfAbsent(
            indexName.toLowerCase(Locale.ROOT),
            ignored -> new IndexAccumulator(!resultSetBoolean(resultSet, "NON_UNIQUE"))
        );
        accumulator.columns().put(resultSet.getInt("ORDINAL_POSITION"), columnName);
      }
    }
    List<PhysicalIndexSnapshot> indexes = new ArrayList<>();
    if (!primaryColumns.isEmpty()) {
      indexes.add(new PhysicalIndexSnapshot("primary", primaryColumns, true, true));
    }
    secondary.forEach((name, accumulator) -> {
      List<String> columns = List.copyOf(accumulator.columns().values());
      if (!columns.equals(primaryColumns)) {
        indexes.add(new PhysicalIndexSnapshot(name, columns, accumulator.unique(), false));
      }
    });
    return indexes;
  }

  private TableIdentity findTable(
      final DatabaseMetaData metadata,
      final String catalog,
      final String schema,
      final String table
  ) throws SQLException {
    try (ResultSet resultSet = metadata.getTables(
        catalog,
        schema,
        escapePattern(metadata, table),
        new String[]{"TABLE", "BASE TABLE"}
    )) {
      while (resultSet.next()) {
        if (table.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
          return new TableIdentity(
              resultSet.getString("TABLE_CAT"),
              resultSet.getString("TABLE_SCHEM"),
              resultSet.getString("TABLE_NAME"),
              resultSet.getString("REMARKS")
          );
        }
      }
    }
    return null;
  }

  private String metadataCatalog(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target
  ) {
    String catalog = normalize(target.metadataTarget().catalogName());
    return coreDialect.metadataCatalog(catalog, context);
  }

  private String metadataSchema(
      final JdbcDatabaseDialect coreDialect,
      final JdbcDatabaseDialect.SupportContext context,
      final PhysicalSchemaTarget target
  ) {
    String schema = normalize(target.metadataTarget().schemaName());
    return coreDialect.metadataSchema(schema, context);
  }

  private static String escapePattern(
      final DatabaseMetaData metadata,
      final String value
  ) throws SQLException {
    String escape = metadata.getSearchStringEscape();
    if (escape == null || escape.isEmpty()) {
      return value;
    }
    return value.replace(escape, escape + escape)
        .replace("_", escape + "_")
        .replace("%", escape + "%");
  }

  private static Integer nullableInt(
      final ResultSet resultSet,
      final String column
  ) throws SQLException {
    int value = resultSet.getInt(column);
    return resultSet.wasNull() ? null : value;
  }

  private static boolean resultSetBoolean(
      final ResultSet resultSet,
      final String column
  ) {
    try {
      return resultSet.getBoolean(column);
    } catch (SQLException exception) {
      throw new IllegalStateException("Cannot read JDBC index metadata", exception);
    }
  }

  static DialectCapability limited(
      final CapabilityConstraintType type,
      final CapabilityConstraintOperator operator,
      final Object value
  ) {
    return new DialectCapability(
        CapabilitySupportLevel.SUPPORTED_WITH_LIMITS,
        Set.of(new DialectCapabilityConstraint(
            type,
            operator,
            value.toString()
        ))
    );
  }

  private static ColumnSpec system(
      final String name,
      final PhysicalStorageType type,
      final boolean nullable,
      final String defaultValue,
      final String comment
  ) {
    return system(name, type, nullable, defaultValue, comment, null);
  }

  private static ColumnSpec system(
      final String name,
      final PhysicalStorageType type,
      final boolean nullable,
      final String defaultValue,
      final String comment,
      final Integer length
  ) {
    return new ColumnSpec(
        new PhysicalColumnSnapshot(
            name,
            type,
            length,
            null,
            null,
            nullable,
            defaultValue,
            comment
        ),
        defaultValue == null ? null : defaultValue.substring(defaultValue.indexOf(':') + 1)
    );
  }

  private static String canonicalLogicalDefault(
      final PhysicalStorageType storageType,
      final LogicalValue value
  ) {
    if (value == null || value.kind() == LogicalValueKind.NULL) {
      return null;
    }
    String canonical = value.canonicalValue();
    if (value.kind() == LogicalValueKind.DECIMAL) {
      canonical = normalizeDecimal(canonical);
    }
    String kind = switch (storageType) {
      case STRING, TEXT -> LogicalValueKind.STRING.name();
      case BOOLEAN -> LogicalValueKind.BOOLEAN.name();
      case INT32, INT64 -> LogicalValueKind.INTEGER.name();
      case DECIMAL -> LogicalValueKind.DECIMAL.name();
      case DATE -> LogicalValueKind.DATE.name();
      case TIME -> LogicalValueKind.TIME.name();
      case DATETIME -> LogicalValueKind.DATETIME.name();
      case UUID -> LogicalValueKind.UUID.name();
      case JSON -> LogicalValueKind.JSON.name();
      case BINARY -> LogicalValueKind.BINARY.name();
    };
    return kind + ':' + canonical;
  }

  private static String normalizeDecimal(final String value) {
    BigDecimal decimal = new BigDecimal(value).stripTrailingZeros();
    if (decimal.scale() < 0) {
      decimal = decimal.setScale(0);
    }
    return decimal.toPlainString();
  }

  private static String normalizeBoolean(final String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "1", "true", "t", "b'1'" -> "true";
      case "0", "false", "f", "b'0'" -> "false";
      default -> value.toLowerCase(Locale.ROOT);
    };
  }

  private static String normalizeDateTime(final String value) {
    return value.replace(' ', 'T').replace("+00", "Z");
  }

  private static String stripQuotedLiteral(final String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1).replace("''", "'");
    }
    return value;
  }

  private static String stripOuterParentheses(final String value) {
    String result = value;
    while (result.length() >= 2 && result.startsWith("(") && result.endsWith(")")) {
      result = result.substring(1, result.length() - 1).trim();
    }
    return result;
  }

  static String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** Compiled column carries normalized verification data and its safe native default. */
  record ColumnSpec(PhysicalColumnSnapshot snapshot, String defaultSql) {
  }

  private record TableIdentity(String catalog, String schema, String name, String comment) {
  }

  private record IndexAccumulator(boolean unique, TreeMap<Integer, String> columns) {
    private IndexAccumulator(final boolean unique) {
      this(unique, new TreeMap<>());
    }
  }
}
