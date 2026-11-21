package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

/** Retirement admission shares the owning policy lock with collection writers and publication. */
final class RecordCollectionRetirement {
  private RecordCollectionRetirement() {}

  static Set<String> validateTransition(
      RecordWritePolicyPublicationCommand command, RecordCalculationStore.Installed previous) {
    var desired = new HashMap<String, RecordCollectionRule>();
    command
        .calculation()
        .collectionsOrEmpty()
        .forEach(rule -> desired.put(rule.relationId(), rule));
    var removed = new HashMap<String, RecordCollectionRule>();
    var retired = new TreeSet<String>();
    if (previous != null) {
      retired.addAll(previous.policy().retiredRelations());
      if (!java.util.Collections.disjoint(retired, desired.keySet())
          || command.calculation().referencesOrEmpty().stream()
              .anyMatch(rule -> retired.contains(rule.relationId()))) {
        throw incompatible();
      }
      for (var rule : previous.policy().definition().collectionsOrEmpty()) {
        var next = desired.get(rule.relationId());
        if (next == null) {
          removed.put(rule.relationId(), rule);
        } else if (!rule.edgeModelId().equals(next.edgeModelId())
            || !rule.targetModelId().equals(next.targetModelId())
            || !rule.targetAppId().equals(next.targetAppId())
            || !rule.targetFormId().equals(next.targetFormId())) {
          throw incompatible();
        }
      }
    }
    for (var retirement : command.retiringCollections()) {
      if (!retirement.equals(removed.remove(retirement.relationId()))) {
        throw incompatible();
      }
      retired.add(retirement.relationId());
    }
    if (!removed.isEmpty()) {
      throw incompatible();
    }
    return retired;
  }

  static void requireEmpty(
      RecordWriteFenceKey owner,
      List<RecordCollectionRule> retiring,
      Map<String, RuntimeRecordModel> edges,
      RecordPublicationSession session) {
    if (edges.size() != retiring.size()) {
      throw incompatible();
    }
    for (var rule : retiring) {
      var edge = edges.get(rule.relationId());
      if (edge == null
          || !rule.edgeModelId().equals(edge.physicalModelId())
          || edge.activeSchema().collection() == null
          || !rule.relationId().equals(edge.activeSchema().collection().relationId())) {
        throw incompatible();
      }
      // Both endpoint columns are null on a retained tombstone. Fail closed for a partial edge.
      var bindingColumns =
          edge.activeSchema().fields().stream()
              .map(field -> RecordSqlCompiler.quote(field.code()) + " IS NOT NULL")
              .toList();
      var result =
          session.read(
              new RecordSelectPlan(
                  "SELECT 1 FROM "
                      + RecordSqlCompiler.table(edge)
                      + " WHERE \"tenant_id\" = ? AND ("
                      + String.join(" OR ", bindingColumns)
                      + ")"
                      + " FETCH NEXT 1 ROWS ONLY",
                  List.of(owner.tenantId()),
                  List.of(),
                  1));
      if (result.truncated()
          || result.returnedRows() != result.rows().size()
          || result.rows().size() > 1) {
        throw incompatible();
      }
      if (!result.rows().isEmpty()) {
        throw new DnaRuntimeException(
            DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE,
            "Existing edges must be detached or migrated before retiring the collection");
      }
    }
  }

  private static DnaRuntimeException incompatible() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
        "The retirement must match the installed collection and preserve its stable storage"
            + " identity");
  }
}
