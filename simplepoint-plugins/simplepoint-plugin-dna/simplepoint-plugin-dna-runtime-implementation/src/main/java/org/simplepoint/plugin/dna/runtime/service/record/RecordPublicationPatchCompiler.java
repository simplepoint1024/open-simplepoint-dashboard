package org.simplepoint.plugin.dna.runtime.service.record;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

/**
 * Compiles immutable computed-column patches against freshly resolved frozen schemas. The worker
 * must hold resource/target maintenance locks and commit these statements with stage/checkpoint CAS.
 * Every affected-row count must be exactly one; an incomplete aggregate must be rolled back.
 */
final class RecordPublicationPatchCompiler {
  private final PhysicalSchemaCodec schemas;
  private final RecordSqlCompiler values;

  RecordPublicationPatchCompiler(final PhysicalSchemaCodec schemas, final RecordSqlCompiler values) {
    this.schemas = Objects.requireNonNull(schemas, "schemas");
    this.values = Objects.requireNonNull(values, "values");
  }

  List<RecordSqlStatement> compile(final RecordPublicationIntent intent, final RecordPublicationAggregate aggregate,
      final Map<String, Resolved> resolved, final Direction direction) {
    Objects.requireNonNull(direction, "direction");
    aggregate.requireIntent(intent);
    var fields = fields(intent, resolved);
    var statements = new ArrayList<RecordSqlStatement>();
    // Parent first, then a deterministic order within owned models; no authorization projection is used.
    var rows = aggregate.rows().stream().sorted(Comparator
        .comparing((RecordPublicationAggregate.Row row) -> row.parentRecordId() != null)
        .thenComparing(RecordPublicationAggregate.Row::modelId).thenComparing(RecordPublicationAggregate.Row::recordId)).toList();
    for (var row : rows) {
      if (rows.size() == 1 && row.changes().isEmpty()) {
        continue;
      }
      statements.add(statement(intent, row, resolved.get(row.modelId()).model(), fields.get(row.modelId()), direction));
    }
    return List.copyOf(statements);
  }

  /** Validate even an empty/no-op batch before allowing a phase or policy transition. */
  void validate(final RecordPublicationIntent intent, final Map<String, Resolved> resolved) {
    fields(intent, resolved);
  }

  private Map<String, Map<String, LogicalFieldDefinition>> fields(final RecordPublicationIntent intent,
      final Map<String, Resolved> resolved) {
    if (!resolved.keySet().equals(intent.bindings().keySet())) {
      throw invalid("Publication model scope changed");
    }
    var fields = new HashMap<String, Map<String, LogicalFieldDefinition>>();
    for (var entry : intent.bindings().entrySet()) {
      String modelId = entry.getKey();
      var frozen = entry.getValue();
      var current = Objects.requireNonNull(resolved.get(modelId), "resolved model");
      var model = current.model();
      boolean parent = intent.key().modelId().equals(modelId);
      if (!modelId.equals(model.physicalModelId()) || !modelId.equals(model.target().physicalModelId())
          || !intent.command().appId().equals(model.appId()) || parent && !intent.command().formId().equals(model.formId())
          || !frozen.target().equals(model.target().metadataTarget())
          || intent.contractVersion() == 1 && frozen.resourceGeneration() != current.resourceGeneration()
          || intent.contractVersion() == 2 && (current.resourceGeneration() != 0 || !intent.resources().get(modelId).equals(current.resources()))
          || model.activeVersion() != frozen.activeSchema().version() || model.requestedVersion() != frozen.requestedSchema().version()
          || !schemas.encode(model.activeSchema()).checksum().equals(frozen.activeSchema().checksum())
          || !schemas.encode(model.requestedSchema()).checksum().equals(frozen.requestedSchema().checksum())) {
        throw invalid("Publication model version, placement or resource generation changed");
      }
      var owner = model.activeSchema().owner();
      if (!Objects.equals(owner, model.requestedSchema().owner()) || parent != (owner == null)
          || owner != null && !owner.parentModelId().equals(intent.key().modelId())) {
        throw invalid("Publication model ownership changed");
      }
      var allowed = new HashMap<String, LogicalFieldDefinition>();
      for (var field : model.requestedSchema().fields()) {
        var active = model.activeField(field.code());
        if (owner == null || !owner.parentFieldId().equals(field.fieldId())
            && !"row_order".equals(field.code()) && !"row_order".equals(active.code())) {
          allowed.put(field.fieldId(), active);
        }
      }
      var formulas = parent ? intent.command().calculation().formulas() : intent.command().calculation().details().values().stream()
          .filter(scope -> scope.physicalModelId().equals(modelId)).findFirst().orElseThrow().formulas();
      if (!allowed.keySet().containsAll(formulas.keySet())
          || formulas.keySet().stream().anyMatch(id -> allowed.get(id).defaultValue() != null)) {
        throw invalid("Publication targets a missing, server-managed or defaulted field");
      }
      fields.put(modelId, Map.copyOf(allowed));
    }
    return Map.copyOf(fields);
  }

  private RecordSqlStatement statement(final RecordPublicationIntent intent, final RecordPublicationAggregate.Row row,
      final RuntimeRecordModel model, final Map<String, LogicalFieldDefinition> fields, final Direction direction) {
    boolean restore = direction == Direction.RESTORE;
    var parameters = new RecordSqlParameters();
    var assignments = new ArrayList<String>();
    for (var entry : row.changes().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      var field = fields.get(entry.getKey());
      // Validate both halves before emitting any SQL, including an invalid candidate during restore.
      Object previous = values.canonicalParameter(field, entry.getValue().previous());
      Object candidate = values.canonicalParameter(field, entry.getValue().candidate());
      assignments.add(quote(field.code()) + " = " + parameters.add(restore ? previous : candidate));
    }
    long appliedVersion = row.previousVersion() + 1;
    long nextVersion = restore ? row.previousVersion() : appliedVersion;
    Instant nextTime = restore ? Instant.parse(row.previousUpdatedAt()) : intent.clock();
    assignments.add("\"record_version\" = " + parameters.add(nextVersion));
    assignments.add("\"updated_by\" = " + parameters.add(restore ? row.previousUpdatedBy() : intent.actorId()));
    assignments.add("\"updated_at\" = " + parameters.add(Timestamp.from(nextTime)));
    String predicate = "\"id\" = " + parameters.add(row.recordId()) + " AND \"tenant_id\" = " + parameters.add(intent.key().tenantId())
        + " AND \"record_version\" = " + parameters.add(restore ? appliedVersion : row.previousVersion())
        + " AND \"updated_by\" = " + parameters.add(restore ? intent.actorId() : row.previousUpdatedBy())
        + " AND \"updated_at\" = " + parameters.add(Timestamp.from(restore ? intent.clock() : Instant.parse(row.previousUpdatedAt())))
        + " AND \"deleted_at\" IS NULL";
    if (row.parentRecordId() != null) {
      String ownerId = model.activeSchema().owner().parentFieldId();
      var owner = model.activeSchema().fields().stream().filter(field -> field.fieldId().equals(ownerId)).findFirst().orElseThrow();
      predicate += " AND " + quote(owner.code()) + " = " + parameters.add(row.parentRecordId());
    }
    return new RecordSqlStatement("UPDATE \"runtime\"." + quote(model.target().metadataTarget().tableName())
        + " SET " + String.join(", ", assignments) + " WHERE " + predicate, parameters.values(), RecordMutationType.UPDATE,
        row.recordId(), nextVersion, nextTime);
  }

  private static String quote(final String identifier) {
    if (identifier == null || !identifier.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
      throw invalid("Invalid frozen publication storage identifier");
    }
    return '"' + identifier + '"';
  }

  private static DnaRuntimeException invalid(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE, message);
  }

  enum Direction {
    APPLY, RESTORE
  }

  /** The caller must resolve generation and schema under the same resource fence. */
  record Resolved(RuntimeRecordModel model, long resourceGeneration, Map<String, RecordPublicationIntent.Resource> resources) {
    Resolved(final RuntimeRecordModel model, final long resourceGeneration) {
      this(model, resourceGeneration, null);
    }

    Resolved {
      Objects.requireNonNull(model, "model");
      resources = resources == null ? null : Map.copyOf(resources);
      if (resourceGeneration < 0) {
        throw new IllegalArgumentException("Resource generation must be nonnegative");
      }
    }
  }
}
