package org.simplepoint.plugin.dna.runtime.service.record;

import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.quote;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Freezes the scalar source and destination edge models for a same-target conversion. Inventory
 * queries include retained records because their physical foreign keys still protect the target.
 */
final class RecordReferenceCollectionConversion {
  private final RecordWriteFenceKey owner;
  private final RecordCollectionBinding destination;
  private final String column;
  private final String fieldId;

  RecordReferenceCollectionConversion(
      RecordWriteFenceKey owner,
      RecordReferenceRule installed,
      RecordCollectionBinding destination) {
    var source = destination.source();
    var target = destination.target();
    if (!owner.modelId().equals(source.physicalModelId())
        || !"parent".equals(installed.source().scope())
        || installed.relationId().equals(destination.relationId())
        || !installed.targetModelId().equals(target.physicalModelId())
        || !installed.targetAppId().equals(target.appId())
        || !installed.targetFormId().equals(target.formId())
        || !target
            .target()
            .metadataTarget()
            .equals(source.target().referenceTargets().get(installed.targetModelId()))) {
      throw incompatible();
    }
    var reference =
        source.activeSchema().references().stream()
            .filter(
                item ->
                    item.relationId().equals(installed.relationId())
                        && item.fieldId().equals(installed.source().fieldId())
                        && item.targetModelId().equals(installed.targetModelId())
                        && !item.unique())
            .findFirst()
            .orElseThrow(RecordReferenceCollectionConversion::incompatible);
    var field =
        source.activeSchema().fields().stream()
            .filter(item -> item.fieldId().equals(reference.fieldId()))
            .filter(item -> item.type() == LogicalFieldType.UUID)
            .findFirst()
            .orElseThrow(RecordReferenceCollectionConversion::incompatible);
    column = field.code();
    fieldId = field.fieldId();
    this.owner = owner;
    this.destination = destination;
  }

  /** Runs while the owning publication excludes ordinary writes. */
  long inspect(RecordPublicationSession session) {
    var edge =
        session.read(
            plan(
                "SELECT 1 FROM "
                    + table(destination.edge())
                    + " WHERE \"tenant_id\" = ? FETCH NEXT 1 ROWS ONLY",
                List.of(owner.tenantId()),
                1));
    if (edge.truncated() || edge.returnedRows() != edge.rows().size() || !edge.rows().isEmpty()) {
      throw conflict();
    }
    String scalar = "src." + quote(column);
    String invalid =
        "NOT EXISTS (SELECT 1 FROM "
            + table(destination.target())
            + " AS dst WHERE dst.\"tenant_id\" = src.\"tenant_id\" AND dst.\"id\" = "
            + scalar
            + " AND (src.\"deleted_at\" IS NOT NULL OR dst.\"deleted_at\" IS NULL))";
    if (destination.source().physicalModelId().equals(destination.target().physicalModelId())) {
      invalid = "(" + invalid + " OR src.\"id\" = " + scalar + ")";
    }
    var violations =
        session.read(
            plan(
                "SELECT src.\"id\"" + sourceWhere() + " AND " + invalid + " FETCH NEXT 1 ROWS ONLY",
                List.of(owner.tenantId()),
                1));
    if (violations.truncated()
        || violations.returnedRows() != violations.rows().size()
        || !violations.rows().isEmpty()) {
      throw conflict();
    }
    return RecordPublicationRows.count(
        session.aggregate(plan("SELECT COUNT(*)" + sourceWhere(), List.of(owner.tenantId()), 1)));
  }

  /** The candidate retains the old physical FK, including the protection of archived rows. */
  static void requireTransition(
      RecordWritePolicyPublicationCommand command, RecordCalculationStore.Installed installed) {
    var old =
        installed == null
            ? List.<RecordReferenceRule>of()
            : installed.policy().definition().referencesOrEmpty();
    var promised = new HashMap<String, RecordReferenceRule>();
    old.stream()
        .filter(RecordReferenceRule::retired)
        .forEach(rule -> promised.put(rule.relationId(), rule));
    var source = command.conversionSource();
    if (source != null) {
      var original =
          old.stream()
              .filter(rule -> rule.relationId().equals(source.relationId()) && !rule.retired())
              .findFirst()
              .orElseThrow(RecordReferenceCollectionConversion::incompatible);
      var target = command.conversionDestination();
      if (!source.source().equals(original.source())
          || !source.targetModelId().equals(original.targetModelId())
          || !source.targetAppId().equals(original.targetAppId())
          || !source.targetFormId().equals(original.targetFormId())
          || !source.targetResourceCode().equals(original.targetResourceCode())
          || !source.targetModelId().equals(target.targetModelId())
          || installed.policy().definition().collectionsOrEmpty().stream()
              .anyMatch(
                  rule ->
                      rule.relationId().equals(target.relationId())
                          || rule.edgeModelId().equals(target.edgeModelId()))
          || old.stream().anyMatch(rule -> rule.relationId().equals(target.relationId()))) {
        throw incompatible();
      }
      promised.put(source.relationId(), source);
    }
    for (var rule : command.calculation().referencesOrEmpty()) {
      if (rule.retired() && !rule.equals(promised.remove(rule.relationId()))) {
        throw incompatible();
      }
    }
    if (!promised.isEmpty()) {
      throw incompatible();
    }
  }

  long activeCount(RecordPublicationSession session) {
    return count("SELECT COUNT(*)" + sourceWhere() + " AND src.\"deleted_at\" IS NULL", session);
  }

  void verifyResult(long expected, boolean restored, RecordPublicationSession session) {
    long edges =
        count(
            "SELECT COUNT(*) FROM " + table(destination.edge()) + " WHERE \"tenant_id\" = ?",
            session);
    if (edges != (restored ? 0 : expected) || activeCount(session) != (restored ? expected : 0)) {
      throw conflict();
    }
  }

  private long count(String sql, RecordPublicationSession session) {
    return RecordPublicationRows.count(session.aggregate(plan(sql, List.of(owner.tenantId()), 1)));
  }

  /** Compiles one edge change from the source value whose exact undo already lives in the stage. */
  void appendChange(
      RecordPublicationIntent intent,
      RecordPublicationAggregate aggregate,
      boolean restore,
      List<RecordSqlStatement> statements,
      List<RecordReferenceCheck> references,
      RecordPublicationSession session) {
    var root =
        aggregate.rows().stream()
            .filter(row -> row.parentRecordId() == null)
            .findFirst()
            .orElseThrow();
    var change = root.changes().get(fieldId);
    if (change == null) {
      return;
    }
    var key =
        RecordCollectionEdgeKey.of(
            destination, owner.tenantId(), root.recordId(), change.previous().canonicalValue());
    var collection = destination.edge().activeSchema().collection();
    references.add(
        new RecordReferenceCheck(
            collection.sourceReferenceId(),
            destination.edge().physicalModelId(),
            key.id(),
            key.sourceId(),
            false));
    references.add(
        new RecordReferenceCheck(
            collection.targetReferenceId(),
            destination.edge().physicalModelId(),
            key.id(),
            key.targetId(),
            false));
    if (restore) {
      statements.add(RecordCollectionSql.removeUnpublished(destination, key, intent));
      return;
    }
    var access =
        new RecordAccessContext(
            new RuntimeActor(owner.tenantId(), intent.actorId()),
            null,
            Set.of(),
            Set.of(),
            Set.of(),
            null,
            department(key, session));
    statements.add(
        RecordCollectionSql.change(
            destination,
            key,
            RecordCollectionEdgeState.absent(),
            -1L,
            CollectionEdgeMutation.Mode.LINK,
            access,
            intent.clock()));
  }

  private String department(RecordCollectionEdgeKey key, RecordPublicationSession session) {
    var result =
        session.read(
            plan(
                "SELECT \"created_org_dept_id\" FROM "
                    + table(destination.source())
                    + " WHERE \"tenant_id\" = ? AND \"id\" = ? AND \"deleted_at\" IS NULL",
                List.of(owner.tenantId(), key.sourceId()),
                2));
    if (result.truncated() || result.rows().size() != 1 || result.rows().getFirst().size() != 1) {
      throw conflict();
    }
    Object value = result.rows().getFirst().getFirst();
    if (value != null && !(value instanceof String)) {
      throw conflict();
    }
    return (String) value;
  }

  /** Stable keyset bounded by the accepted conversion scope, including possible legacy rows. */
  List<Entry> page(String cursor, int limit, RecordPublicationSession session) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Conversion batch must contain 1 to 100 references");
    }
    var args = new ArrayList<Object>();
    args.add(owner.tenantId());
    String after = "";
    if (cursor != null) {
      RecordPublicationAggregate.requireId(cursor);
      args.add(cursor);
      after = " AND src.\"id\" > ?";
    }
    var rows =
        session.read(
            plan(
                "SELECT src.\"id\", src."
                    + quote(column)
                    + ", src.\"record_version\""
                    + sourceWhere()
                    + after
                    + " ORDER BY src.\"id\" ASC FETCH NEXT "
                    + limit
                    + " ROWS ONLY",
                args,
                limit));
    if (rows.truncated()
        || rows.returnedRows() != rows.rows().size()
        || rows.rows().size() > limit) {
      throw conflict();
    }
    var entries = new ArrayList<Entry>();
    var ids = new HashSet<String>();
    for (var row : rows.rows()) {
      if (row.size() != 3
          || !(row.getFirst() instanceof String sourceId)
          || !(row.get(2) instanceof Number version)
          || sourceId.equals(cursor)
          || !ids.add(sourceId)) {
        throw conflict();
      }
      try {
        RecordPublicationAggregate.requireId(sourceId);
        String targetId = RecordReferenceRules.identity(row.get(1));
        RecordPublicationAggregate.requireId(targetId);
        var key = RecordCollectionEdgeKey.of(destination, owner.tenantId(), sourceId, targetId);
        long revision = new BigDecimal(version.toString()).longValueExact();
        if (revision < 0 || revision == Long.MAX_VALUE) {
          throw conflict();
        }
        entries.add(new Entry(key, revision));
      } catch (IllegalArgumentException | ArithmeticException failure) {
        throw conflict();
      }
    }
    return List.copyOf(entries);
  }

  private String sourceWhere() {
    return " FROM "
        + table(destination.source())
        + " AS src WHERE src.\"tenant_id\" = ? AND src."
        + quote(column)
        + " IS NOT NULL";
  }

  private static RecordSelectPlan plan(String sql, List<Object> parameters, int limit) {
    return new RecordSelectPlan(sql, parameters, List.of(), limit).requirePhysicalRelations();
  }

  private static DnaRuntimeException incompatible() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
        "Conversion source and destination do not share the installed reference target");
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE,
        "Existing references or destination edges conflict with the collection conversion");
  }

  record Entry(RecordCollectionEdgeKey key, long sourceVersion) {}
}
