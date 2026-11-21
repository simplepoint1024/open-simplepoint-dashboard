package org.simplepoint.plugin.dna.runtime.service.record;

import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.quote;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordSqlCompiler.table;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition;

/** Same-edge-store semi-join in both directions; neither raw edge counts nor client-side paging. */
record RecordCollectionSelection(
    RecordCollectionBinding binding,
    Direction direction,
    String fixedRecordId,
    RecordAccessContext fixedAccess,
    RecordValueCondition fixedCondition,
    boolean candidates) {
  RecordCollectionSelection(
      RecordCollectionBinding binding,
      Direction direction,
      String fixedRecordId,
      RecordAccessContext fixedAccess) {
    this(binding, direction, fixedRecordId, fixedAccess, null, false);
  }

  enum Direction {
    FROM_SOURCE,
    FROM_TARGET
  }

  RecordCollectionSelection {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(fixedAccess, "fixedAccess");
    var canonical = UUID.fromString(fixedRecordId).toString();
    if (!canonical.equalsIgnoreCase(fixedRecordId)) {
      throw RecordReferenceAdmission.incompatible();
    }
    fixedRecordId = canonical;
  }

  String predicate(
      RecordSqlCompiler compiler,
      RuntimeRecordModel resultModel,
      RecordAccessContext resultAccess,
      RecordSqlParameters parameters) {
    boolean forward = direction == Direction.FROM_SOURCE;
    var fixed = forward ? binding.source() : binding.target();
    var result = forward ? binding.target() : binding.source();
    if (!result.equals(resultModel)
        || !resultAccess.actor().equals(fixedAccess.actor())
        || !resultAccess.queryable(resultModel, "id")) {
      throw RecordReferenceAdmission.incompatible();
    }
    // The exact fixed root's current row policy is part of both count and page SQL. Resolving a
    // source once outside this query cannot keep granting collection access after a scope change.
    String distinct =
        candidates && fixed.physicalModelId().equals(result.physicalModelId())
            ? " AND " + quote("id") + " <> " + parameters.add(fixedRecordId)
            : "";
    var guard =
        compiler.referenceTargets(fixed, fixedAccess, List.of(fixedRecordId), fixedCondition);
    guard.parameters().forEach(parameters::add);
    String resultColumn = quote(forward ? binding.targetColumn() : binding.sourceColumn());
    String edges =
        " FROM "
            + table(binding.edge())
            + " WHERE "
            + quote("tenant_id")
            + " = "
            + parameters.add(resultAccess.actor().tenantId())
            + " AND "
            + quote(forward ? binding.sourceColumn() : binding.targetColumn())
            + " = "
            + parameters.add(fixedRecordId)
            + " AND "
            + quote("deleted_at")
            + " IS NULL";
    String membership =
        candidates
            ? " AND NOT EXISTS (SELECT 1"
                + edges
                + " AND "
                + resultColumn
                + " = "
                + table(resultModel)
                + "."
                + quote("id")
                + ")"
            : " AND " + quote("id") + " IN (SELECT " + resultColumn + edges + ")";
    return distinct + " AND EXISTS (" + guard.sql() + ")" + membership;
  }
}
