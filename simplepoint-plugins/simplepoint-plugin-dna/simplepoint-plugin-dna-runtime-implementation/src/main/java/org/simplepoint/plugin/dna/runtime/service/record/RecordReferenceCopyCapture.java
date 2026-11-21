package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Captures protected origins after aggregate writes, through the same physical transaction. */
final class RecordReferenceCopyCapture {
  private final RecordReferenceCopyStore store = new RecordReferenceCopyStore();

  RecordStoredSnapshot capture(
      RecordOperationIdentity operation,
      RecordStoredSnapshot snapshot,
      Function<RecordSelectPlan, CalciteQueryResult> query) {
    var values = snapshot.values();
    Map<String, List<RecordView>> models = new LinkedHashMap<>();
    models.put(operation.modelId(), List.of(values.parent()));
    if (values.details().containsKey(operation.modelId())) {
      throw invalid();
    }
    models.putAll(values.details());
    if (models.values().stream().mapToInt(List::size).sum() > 201) {
      throw invalid();
    }
    List<RecordReferenceCopySnapshot> captured = new ArrayList<>();
    for (var entry : models.entrySet()) {
      var rows = entry.getValue();
      for (int offset = 0; offset < rows.size(); offset += 100) {
        var batch = rows.subList(offset, Math.min(offset + 100, rows.size()));
        var keys =
            batch.stream()
                .map(
                    row ->
                        new RecordReferenceCopyStore.Key(
                            operation.tenantId(), entry.getKey(), row.id()))
                .toList();
        var stored = store.readBatch(keys, query.apply(store.lookupBatch(keys)));
        for (var row : batch) {
          var origins = stored.get(row.id());
          if (origins == null) {
            continue;
          }
          // A changed business row must advance its ledger even when its retained origins are
          // unchanged. Never label stale provenance as belonging to a newer aggregate revision.
          if (origins.sourceVersion() != row.version()) {
            throw invalid();
          }
          if (!origins.origins().isEmpty() || !origins.derivedOrigins().isEmpty()) {
            captured.add(
                new RecordReferenceCopySnapshot(
                    entry.getKey().equals(operation.modelId()) ? null : entry.getKey(),
                    row.id(),
                    row.version(),
                    origins.origins(),
                    origins.derivedOrigins()));
          }
        }
      }
    }
    return snapshot.withCopyOrigins(captured);
  }

  private static DnaRuntimeException invalid() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        "Snapshot copy provenance does not match the committed aggregate");
  }
}
