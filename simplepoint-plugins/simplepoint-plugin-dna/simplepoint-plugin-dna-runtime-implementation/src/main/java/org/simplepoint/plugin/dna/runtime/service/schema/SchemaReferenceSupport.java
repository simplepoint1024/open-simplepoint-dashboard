package org.simplepoint.plugin.dna.runtime.service.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Action;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Deferrability;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

/** Tenant-qualified reference compilation, separate from ordinary column/index evolution. */
final class SchemaReferenceSupport {
  private SchemaReferenceSupport() {}

  static List<PhysicalForeignKeySnapshot> expected(
      AbstractRuntimeSchemaDialect dialect,
      PhysicalSchemaTarget source,
      PhysicalSchemaDefinition schema,
      JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context) {
    return schema.references().stream()
        .map(
            reference -> {
              var target = resolve(source, reference.targetModelId());
              var column =
                  schema.fields().stream()
                      .filter(field -> field.fieldId().equals(reference.fieldId()))
                      .findFirst()
                      .orElseThrow();
              String key =
                  dialect.snapshotCodec.statementChecksum(
                      "REFERENCE",
                      source.metadataTarget().tableName() + "|" + reference.relationId());
              // PostgreSQL uses a null catalog as a metadata-query filter, but imported keys report
              // the actual database.
              String catalog = core.metadataCatalog(blank(target.catalogName()), context);
              if (catalog == null) {
                catalog = blank(context.currentCatalog());
              }
              return new PhysicalForeignKeySnapshot(
                  "fk_" + key.substring(0, 24),
                  List.of("tenant_id", column.code()),
                  catalog,
                  core.metadataSchema(blank(target.schemaName()), context),
                  target.tableName(),
                  List.of("tenant_id", "id"),
                  Action.RESTRICT,
                  Action.RESTRICT,
                  Deferrability.NOT_DEFERRABLE);
            })
        .toList();
  }

  static void validateTargets(
      AbstractRuntimeSchemaDialect dialect,
      Connection connection,
      JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context,
      PhysicalSchemaTarget source,
      PhysicalSchemaDefinition schema,
      PhysicalTableSnapshot desired)
      throws SQLException {
    for (var reference : schema.references()) {
      var target = resolve(source, reference.targetModelId());
      boolean self = source.physicalModelId().equals(reference.targetModelId());
      if (self && !target.equals(source.metadataTarget())) {
        throw invalid("Self reference has a different storage target");
      }
      var actual =
          self
              ? desired
              : dialect.inspect(
                  connection,
                  core,
                  context,
                  new PhysicalSchemaTarget(reference.targetModelId(), target));
      if (!actual.exists()
          || actual.indexes().stream()
              .noneMatch(
                  index -> index.unique() && index.columns().equals(List.of("tenant_id", "id")))) {
        throw invalid("Reference target requires a published tenant-qualified identity key");
      }
      String sourceColumn =
          schema.fields().stream()
              .filter(field -> field.fieldId().equals(reference.fieldId()))
              .findFirst()
              .orElseThrow()
              .code();
      if (!sameType(column(desired, sourceColumn), column(actual, "id"))
          || !sameType(column(desired, "tenant_id"), column(actual, "tenant_id"))
          || column(actual, "id").nullable()
          || column(actual, "tenant_id").nullable()) {
        throw invalid("Reference columns and target identity have incompatible physical types");
      }
    }
  }

  static void validateExistingRows(
      AbstractRuntimeSchemaDialect dialect,
      Connection connection,
      JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context,
      PhysicalSchemaTarget source,
      PhysicalSchemaDefinition previous,
      PhysicalSchemaDefinition desired,
      PhysicalTableSnapshot actual)
      throws SQLException {
    if (!actual.exists()) {
      return;
    }
    String sourceTable = dialect.qualifiedTable(core, context, source);
    String tenant = core.quoteIdentifier("tenant_id", context);
    String id = core.quoteIdentifier("id", context);
    String deleted = core.quoteIdentifier("deleted_at", context);
    for (var reference : desired.references()) {
      var fields = previous == null ? desired.fields() : previous.fields();
      var field =
          fields.stream()
              .filter(value -> value.fieldId().equals(reference.fieldId()))
              .findFirst()
              .orElse(null);
      // A new nullable reference column is initially NULL; reference defaults are forbidden by the
      // contract.
      if (field == null
          || actual.columns().stream().noneMatch(value -> value.name().equals(field.code()))) {
        continue;
      }
      var target = resolve(source, reference.targetModelId());
      String targetTable =
          dialect.qualifiedTable(
              core, context, new PhysicalSchemaTarget(reference.targetModelId(), target));
      String value = core.quoteIdentifier(field.code(), context);
      String orphan =
          "SELECT 1 FROM "
              + sourceTable
              + " s LEFT JOIN "
              + targetTable
              + " t ON s."
              + tenant
              + " = t."
              + tenant
              + " AND s."
              + value
              + " = t."
              + id
              + " WHERE s."
              + value
              + " IS NOT NULL AND (t."
              + id
              + " IS NULL OR t."
              + deleted
              + " IS NOT NULL"
              + (source.physicalModelId().equals(reference.targetModelId())
                  ? " OR s." + id + " = t." + id
                  : "")
              + ") LIMIT 1";
      rejectRows(
          connection,
          orphan,
          "Existing reference values have missing, deleted, cross-tenant or forbidden self"
              + " targets");
      if (reference.unique()) {
        rejectRows(
            connection,
            "SELECT 1 FROM "
                + sourceTable
                + " WHERE "
                + value
                + " IS NOT NULL GROUP BY "
                + tenant
                + ", "
                + value
                + " HAVING COUNT(*) > 1 LIMIT 1",
            "Existing records violate one-to-one reference uniqueness");
      }
    }
  }

  private static void rejectRows(Connection connection, String sql, String message)
      throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setQueryTimeout(30);
      try (var rows = statement.executeQuery(sql)) {
        if (rows.next()) {
          throw new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_PRECHECK_FAILED, message);
        }
      }
    }
  }

  static void validateEvolution(
      PhysicalSchemaDefinition previous, PhysicalSchemaDefinition target) {
    if (!Objects.equals(previous.collection(), target.collection())) {
      throw invalid("Collection ownership cannot change through ordinary schema evolution");
    }
    if (previous.references().stream()
        .anyMatch(reference -> target.references().stream().noneMatch(reference::canEvolveTo))) {
      throw invalid("Removing or replacing a reference requires an explicit relation migration");
    }
  }

  static String addSql(
      AbstractRuntimeSchemaDialect dialect,
      JdbcDatabaseDialect core,
      JdbcDatabaseDialect.SupportContext context,
      String source,
      PhysicalForeignKeySnapshot key) {
    var target =
        new PhysicalSchemaTarget(
            "reference",
            new RuntimeMetadataTarget(
                "resolved", key.targetCatalog(), key.targetSchema(), key.targetTable()));
    return "ALTER TABLE "
        + source
        + " ADD CONSTRAINT "
        + core.quoteIdentifier(key.name(), context)
        + " FOREIGN KEY ("
        + identifiers(core, context, key.columns())
        + ") REFERENCES "
        + dialect.qualifiedTable(core, context, target)
        + " ("
        + identifiers(core, context, key.targetColumns())
        + ")"
        + " ON UPDATE RESTRICT ON DELETE RESTRICT";
  }

  private static RuntimeMetadataTarget resolve(PhysicalSchemaTarget source, String modelId) {
    RuntimeMetadataTarget target = source.referenceTargets().get(modelId);
    var local = source.metadataTarget();
    if (target == null
        || !local.dataSourceId().equals(target.dataSourceId())
        || !local.catalogName().equals(target.catalogName())
        || !local.schemaName().equals(target.schemaName())) {
      throw invalid(
          "Reference target must be resolved in the same registered datasource and namespace");
    }
    return target;
  }

  private static PhysicalColumnSnapshot column(PhysicalTableSnapshot table, String name) {
    return table.columns().stream()
        .filter(column -> column.name().equals(name))
        .findFirst()
        .orElseThrow(() -> invalid("Reference identity column is missing"));
  }

  private static boolean sameType(PhysicalColumnSnapshot left, PhysicalColumnSnapshot right) {
    return left.storageType() == right.storageType()
        && Objects.equals(left.length(), right.length())
        && Objects.equals(left.precision(), right.precision())
        && Objects.equals(left.scale(), right.scale());
  }

  private static String identifiers(
      JdbcDatabaseDialect core, JdbcDatabaseDialect.SupportContext context, List<String> names) {
    return names.stream()
        .map(name -> core.quoteIdentifier(name, context))
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static DnaRuntimeException invalid(String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.SCHEMA_INVALID, message);
  }
}
