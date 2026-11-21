package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;

/** Identity-only check for historical owned dependencies, after their current roots are visible. */
final class RecordReferenceOwnedVisibility {
  private RecordReferenceOwnedVisibility() {}

  static Set<Row> select(
      RuntimeRecordModel model,
      RecordAccessContext access,
      String parentCode,
      List<Row> requested,
      RecordSqlCompiler compiler,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var result = reader.apply(compiler.referenceOwnedTargets(model, access, parentCode, requested));
    if (result.truncated() || result.rows().size() > requested.size()) {
      throw RecordReferenceSnapshotProvenance.invalid();
    }
    var allowed = new LinkedHashSet<Row>();
    for (var row : result.rows()) {
      if (row.size() != 2
          || !(row.get(0) instanceof String id)
          || !(row.get(1) instanceof String parentId)) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
      var identity = new Row(id, parentId);
      if (!requested.contains(identity) || !allowed.add(identity)) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
    }
    return Set.copyOf(allowed);
  }

  record Row(String recordId, String parentId) {
    Row {
      if (recordId == null
          || parentId == null
          || !java.util.UUID.fromString(recordId).toString().equals(recordId)
          || !java.util.UUID.fromString(parentId).toString().equals(parentId)) {
        throw RecordReferenceSnapshotProvenance.invalid();
      }
    }
  }
}
