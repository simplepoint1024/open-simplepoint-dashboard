package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Action;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Deferrability;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcForeignKeyInspector;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordReferenceSql;

/**
 * Physical reference integrity inside the existing Calcite write transaction.
 *
 * Call only AFTER a successful business mutation: its table/metadata lock fences concurrent FK
 * installation before discovering references. A target SHARE row lock fences soft deletion until
 * commit. Conversely, a soft delete holds its update lock while inspecting all retained references.
 * Native SQL here only inspects identity/integrity and acquires locks, like RecordWriteFence; it is
 * not a second CRUD/query engine and does not authorize any reference or expose business values.
 * The published relation policy must independently enforce resource/row/field access and eligibility.
 * Incoming relation governance must also fence schema rebuilds: a temporarily dropped FK cannot be
 * interpreted as permission to delete its target. Keep relation publication gated until that catalog
 * and its shared write/publication admission are installed.
 */
final class RecordReferenceIntegrity {
  private final Connection connection;
  private final ManagedRuntimeDataSource handle;
  private final String tenantId;
  private final RecordExecutionDeadline deadline;
  private final Map<String, RuntimeRecordModel> models = new HashMap<>();
  // Populated only after mutation obtains table/metadata locks; lifetime is this target transaction.
  private final Map<String, List<PhysicalForeignKeySnapshot>> outgoingKeys = new HashMap<>();
  private final Map<String, List<JdbcForeignKeyInspector.ForeignKeyLink>> incomingKeys = new HashMap<>();

  RecordReferenceIntegrity(Connection connection, ManagedRuntimeDataSource handle, String tenantId,
      RuntimeRecordModel root, List<RuntimeRecordModel> children, RecordExecutionDeadline deadline) {
    this.connection = connection;
    this.handle = handle;
    this.tenantId = tenantId;
    this.deadline = deadline;
    models.put(root.physicalModelId(), root);
    for (var child : children) {
      if (models.putIfAbsent(child.physicalModelId(), child) != null
          || !root.target().metadataTarget().dataSourceId().equals(child.target().metadataTarget().dataSourceId())
          || !root.target().metadataTarget().catalogName().equals(child.target().metadataTarget().catalogName())
          || !root.target().metadataTarget().schemaName().equals(child.target().metadataTarget().schemaName())) {
        throw incompatible();
      }
    }
  }

  void afterMutation(RecordSqlStatement statement) throws SQLException {
    var subject = statement.subject();
    if (subject == null) return; // Journal, snapshot and publication infrastructure have no business subject.
    var model = models.get(subject.modelId());
    if (model == null || !tenantId.equals(subject.tenantId())) throw incompatible();
    if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
      throw incompatible();
    }
    deadline.remainingMillis();
    String catalog = handle.metadataCatalog() == null ? connection.getCatalog() : handle.metadataCatalog();
    String schema = handle.metadataSchema();
    String table = model.target().metadataTarget().tableName();
    if (statement.mutationType() == RecordMutationType.DELETE) {
      var incoming = incomingKeys.get(table);
      if (incoming == null) {
        incoming = JdbcForeignKeyInspector.incoming(connection.getMetaData(), catalog, schema, table);
        incomingKeys.put(table, incoming);
      }
      for (var link : incoming) {
        var key = link.constraint();
        validate(key, catalog, schema);
        if (!Objects.equals(catalog, link.sourceCatalog()) || !Objects.equals(schema, link.sourceSchema())) {
          throw incompatible();
        }
        var source = target(model, link.sourceCatalog(), link.sourceSchema(), link.sourceTable());
        try (var query = prepare(sql(source, key.columns().get(1)).incoming(), statement.recordId());
             var rows = query.executeQuery()) {
          // No row-scope/deleted filter: offline, hidden and soft-deleted sources still retain references.
          if (rows.next()) throw conflict();
        }
      }
    } else {
      var keys = outgoingKeys.get(table);
      if (keys == null) {
        keys = JdbcForeignKeyInspector.inspect(connection.getMetaData(), catalog, schema, table);
        outgoingKeys.put(table, keys);
      }
      var declaredColumns = model.activeSchema().references().stream().map(reference ->
          model.activeSchema().fields().stream().filter(field -> field.fieldId().equals(reference.fieldId()))
              .findFirst().orElseThrow(RecordReferenceIntegrity::incompatible).code()).sorted().toList();
      for (var key : keys) {
        validate(key, catalog, schema);
        var field = model.activeSchema().fields().stream().filter(value -> value.code().equals(key.columns().get(1)))
            .findFirst().orElseThrow(RecordReferenceIntegrity::incompatible);
        var definition = model.activeSchema().references().stream().filter(value -> value.fieldId().equals(field.fieldId()))
            .findFirst().orElseThrow(RecordReferenceIntegrity::incompatible);
        var expected = model.target().referenceTargets().get(definition.targetModelId());
        if (expected == null || !model.target().metadataTarget().dataSourceId().equals(expected.dataSourceId())
            || !expected.tableName().equals(key.targetTable())
            || !model.target().metadataTarget().catalogName().equals(expected.catalogName())
            || !model.target().metadataTarget().schemaName().equals(expected.schemaName())) throw incompatible();
      }
      if (!keys.stream().map(key -> key.columns().get(1)).sorted().toList().equals(declaredColumns)) {
        throw incompatible(); // Missing or unowned constraints cannot silently weaken/replace the active contract.
      }
      for (var key : keys) {
        String referenced;
        try (var query = prepare(sql(model.target(), key.columns().get(1)).value(), statement.recordId());
             var rows = query.executeQuery()) {
          if (!rows.next()) throw incompatible();
          referenced = rows.getString(1);
          if (rows.next()) throw incompatible();
        }
        if (referenced == null) continue;
        if (table.equals(key.targetTable()) && statement.recordId().equals(referenced)) throw conflict();
        var target = target(model, key.targetCatalog(), key.targetSchema(), key.targetTable());
        try (var query = prepare(sql(target, "id").lockTarget(), referenced);
             var rows = query.executeQuery()) {
          if (!rows.next() || rows.getObject(1) != null) throw conflict();
          if (rows.next()) throw incompatible();
        }
      }
    }
    deadline.remainingMillis();
  }

  private PreparedStatement prepare(String sql, String recordId) throws SQLException {
    var query = connection.prepareStatement(sql);
    try {
      query.setQueryTimeout(Math.max(1, (deadline.remainingMillis() + 999) / 1000));
      query.setString(1, tenantId);
      query.setString(2, recordId);
      return query;
    } catch (RuntimeException | SQLException failure) {
      try { query.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
      throw failure;
    }
  }

  private RecordReferenceSql sql(PhysicalSchemaTarget target, String column) {
    return handle.runtimeDialect().recordReferenceSql(handle.coreDialect(), handle.supportContext(), target, column);
  }

  private static PhysicalSchemaTarget target(RuntimeRecordModel source, String catalog, String schema, String table) {
    return new PhysicalSchemaTarget("reference-integrity",
        new RuntimeMetadataTarget(source.target().metadataTarget().dataSourceId(), catalog, schema, table));
  }

  private static void validate(PhysicalForeignKeySnapshot key, String catalog, String schema) {
    if (key.columns().size() != 2 || !"tenant_id".equals(key.columns().getFirst())
        || !key.targetColumns().equals(List.of("tenant_id", "id"))
        || !Objects.equals(catalog, key.targetCatalog()) || !Objects.equals(schema, key.targetSchema())
        || !List.of(Action.RESTRICT, Action.NO_ACTION).contains(key.deleteAction())
        || !List.of(Action.RESTRICT, Action.NO_ACTION).contains(key.updateAction())
        || key.deferrability() != Deferrability.NOT_DEFERRABLE) throw incompatible();
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The operation conflicts with retained record references");
  }

  private static DnaRuntimeException incompatible() {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
        "Record reference storage is incompatible with the admitted model");
  }
}
