package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Authorized read plans executed after the mutations, on the same physical transaction. */
record RecordSnapshotPlan(String parentId, long parentVersion, List<Part> parts) {
  record Part(
      RuntimeRecordModel model,
      RecordAccessContext access,
      RecordSelectPlan select,
      boolean parent) {}

  OwnedRecordView capture(Function<RecordSelectPlan, CalciteQueryResult> query) {
    Map<String, Object> values = new LinkedHashMap<>();
    Map<String, List<RecordView>> details = new LinkedHashMap<>();
    for (var part : parts) {
      var rows =
          RecordQueryServiceImpl.views(
              query.apply(part.select()), part.select(), part.model(), part.access());
      if (part.parent()) {
        if (rows.size() != 1
            || !rows.getFirst().id().equals(parentId)
            || rows.getFirst().version() != parentVersion) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
              "Cannot capture the committed parent revision");
        }
        values.putAll(rows.getFirst().values());
      } else {
        if (rows.size() > 40) {
          throw new DnaRuntimeException(
              DnaRuntimeErrorCode.RECORD_COMMAND_INVALID, "Snapshot child row budget exceeded");
        }
        details.put(part.model().physicalModelId(), new ArrayList<>(rows));
      }
    }
    return new OwnedRecordView(new RecordView(parentId, parentVersion, values), details);
  }
}
