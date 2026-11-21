package org.simplepoint.plugin.dna.runtime.service.record;

import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.quote;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.table;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;

/** Internal edge SQL; no ordinary record API can obtain an edge access context. */
final class RecordCollectionSql {
  private RecordCollectionSql() {}

  static RecordSelectPlan lookup(RecordCollectionBinding binding, RecordCollectionEdgeKey key) {
    return lookup(binding, List.of(key));
  }

  /** One bounded read supplies edge observations for a page/selection without per-row queries. */
  static RecordSelectPlan lookup(
      RecordCollectionBinding binding, List<RecordCollectionEdgeKey> keys) {
    requireKeys(binding, keys);
    var parameters = new RecordSqlParameters();
    return new RecordSelectPlan(
            "SELECT "
                + List.of(
                        "id",
                        "tenant_id",
                        "record_version",
                        "deleted_at",
                        binding.sourceColumn(),
                        binding.targetColumn())
                    .stream()
                    .map(RecordSqlCompiler::quote)
                    .collect(Collectors.joining(", "))
                + " FROM "
                + table(binding.edge())
                + " WHERE "
                + quote("tenant_id")
                + " = "
                + parameters.add(keys.getFirst().tenantId())
                + " AND "
                + quote("id")
                + " IN ("
                + keys.stream()
                    .map(key -> parameters.add(key.id()))
                    .collect(Collectors.joining(", "))
                + ")",
            parameters.values(),
            List.of(),
            keys.size())
        .requirePhysicalRelations();
  }

  /** Returns no statement for a verified no-op; the owning transaction still validates versions. */
  static RecordSqlStatement change(
      RecordCollectionBinding binding,
      RecordCollectionEdgeKey key,
      RecordCollectionEdgeState state,
      CollectionEdgeMutation intent,
      RecordAccessContext sourceAccess,
      Instant now) {
    if (!key.targetId().equals(intent.targetRecordId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    return change(
        binding, key, state, intent.expectedEdgeVersion(), intent.mode(), sourceAccess, now);
  }

  /** The owning maintenance operation already holds the source policy and endpoint locks. */
  static RecordSqlStatement change(
      RecordCollectionBinding binding,
      RecordCollectionEdgeKey key,
      RecordCollectionEdgeState state,
      long expectedEdgeVersion,
      CollectionEdgeMutation.Mode mode,
      RecordAccessContext sourceAccess,
      Instant now) {
    requireKey(binding, key);
    if (!key.tenantId().equals(sourceAccess.actor().tenantId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    var changed = state.transition(expectedEdgeVersion, mode);
    if (state.equals(changed)) {
      return null;
    }
    var values = new LinkedHashMap<String, Object>();
    if (state.version() == -1) {
      values.put("id", key.id());
      values.put("tenant_id", key.tenantId());
      values.put("created_by", sourceAccess.actor().actorId());
      values.put("created_org_dept_id", sourceAccess.organizationId());
      values.put("created_at", Timestamp.from(now));
      values.put("deleted_at", null);
    }
    values.put(binding.sourceColumn(), changed.active() ? key.sourceId() : null);
    values.put(binding.targetColumn(), changed.active() ? key.targetId() : null);
    values.put("record_version", changed.version());
    values.put("updated_by", sourceAccess.actor().actorId());
    values.put("updated_at", Timestamp.from(now));
    var parameters = new RecordSqlParameters();
    String sql;
    if (state.version() == -1) {
      sql =
          "INSERT INTO "
              + table(binding.edge())
              + " ("
              + values.keySet().stream()
                  .map(RecordSqlCompiler::quote)
                  .collect(Collectors.joining(", "))
              + ") VALUES ("
              + values.values().stream().map(parameters::add).collect(Collectors.joining(", "))
              + ")";
    } else {
      sql =
          "UPDATE "
              + table(binding.edge())
              + " SET "
              + values.entrySet().stream()
                  .map(entry -> quote(entry.getKey()) + " = " + parameters.add(entry.getValue()))
                  .collect(Collectors.joining(", "))
              + " WHERE "
              + quote("tenant_id")
              + " = "
              + parameters.add(key.tenantId())
              + " AND "
              + quote("id")
              + " = "
              + parameters.add(key.id())
              + " AND "
              + quote("record_version")
              + " = "
              + parameters.add(state.version())
              + " AND "
              + quote("deleted_at")
              + " IS NULL"
              + endpointCondition(
                  binding.sourceColumn(), state.active() ? key.sourceId() : null, parameters)
              + endpointCondition(
                  binding.targetColumn(), state.active() ? key.targetId() : null, parameters);
    }
    return new RecordSqlStatement(
        sql,
        parameters.values(),
        state.version() == -1 ? RecordMutationType.CREATE : RecordMutationType.UPDATE,
        key.id(),
        changed.version(),
        now,
        new RecordSqlStatement.Subject(
            binding.edge().physicalModelId(), key.tenantId(), sourceAccess.actor().actorId()));
  }

  private static String endpointCondition(
      String column, String value, RecordSqlParameters parameters) {
    return " AND " + quote(column) + (value == null ? " IS NULL" : " = " + parameters.add(value));
  }

  /** Only an edge inserted into a previously empty, unpublished destination may be removed. */
  static RecordSqlStatement removeUnpublished(
      RecordCollectionBinding binding, RecordCollectionEdgeKey key, RecordPublicationIntent intent) {
    requireKey(binding, key);
    if (!intent.key().tenantId().equals(key.tenantId())
        || !intent.key().modelId().equals(binding.source().physicalModelId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    return new RecordSqlStatement(
        "DELETE FROM " + table(binding.edge())
            + " WHERE \"tenant_id\" = ? AND \"id\" = ? AND \"record_version\" = 0"
            + " AND \"created_by\" = ? AND \"updated_by\" = ? AND \"deleted_at\" IS NULL"
            + " AND " + quote(binding.sourceColumn()) + " = ? AND " + quote(binding.targetColumn()) + " = ?",
        List.of(key.tenantId(), key.id(), intent.actorId(), intent.actorId(), key.sourceId(), key.targetId()),
        RecordMutationType.DELETE, key.id(), 0, intent.clock(),
        new RecordSqlStatement.Subject(binding.edge().physicalModelId(), key.tenantId(), intent.actorId()));
  }

  private static void requireKey(RecordCollectionBinding binding, RecordCollectionEdgeKey key) {
    Objects.requireNonNull(binding, "binding");
    if (!key.equals(
        RecordCollectionEdgeKey.of(binding, key.tenantId(), key.sourceId(), key.targetId()))) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  static void requireKeys(RecordCollectionBinding binding, List<RecordCollectionEdgeKey> keys) {
    if (keys == null
        || keys.isEmpty()
        || keys.size() > 100
        || keys.stream().anyMatch(Objects::isNull)
        || keys.stream().map(RecordCollectionEdgeKey::id).distinct().count() != keys.size()
        || keys.stream().map(RecordCollectionEdgeKey::tenantId).distinct().count() != 1) {
      throw RecordReferenceAdmission.incompatible();
    }
    keys.forEach(key -> requireKey(binding, key));
  }
}
