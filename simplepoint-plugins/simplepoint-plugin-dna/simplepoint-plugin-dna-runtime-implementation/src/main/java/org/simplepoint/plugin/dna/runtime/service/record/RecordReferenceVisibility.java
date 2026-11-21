package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

/**
 * Shared identity-only target reads; callers retain the policy/resource fence and total deadline.
 */
final class RecordReferenceVisibility {
  private RecordReferenceVisibility() {}

  static boolean readable(RuntimeRecordModel model, RecordAccessContext access, String fieldId) {
    var field =
        model.requestedSchema().fields().stream()
            .filter(value -> value.fieldId().equals(fieldId))
            .findFirst()
            .orElse(null);
    return field != null
        && (field.type() == LogicalFieldType.STRING || field.type() == LogicalFieldType.TEXT)
        && access.resourceGeneration() > 0
        && access.readableFields().containsAll(List.of("id", field.code()))
        && !access.maskedFields().contains("id")
        && !access.maskedFields().contains(field.code());
  }

  static Set<String> select(
      RuntimeRecordModel model,
      RecordAccessContext access,
      List<String> ids,
      RecordSqlCompiler compiler,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var query = compiler.referenceTargets(model, access, ids, null);
    var result = reader.apply(query);
    if (result.truncated()) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    Set<String> found = new LinkedHashSet<>();
    for (var row : result.rows()) {
      if (row.size() != 1
          || !(row.getFirst() instanceof String id)
          || !ids.contains(id)
          || !found.add(id)) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
    }
    return Set.copyOf(found);
  }
}
