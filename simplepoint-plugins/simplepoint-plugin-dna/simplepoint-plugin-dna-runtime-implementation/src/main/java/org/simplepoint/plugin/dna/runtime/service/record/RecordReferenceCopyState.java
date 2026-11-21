package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Bounded transaction-local origin inputs shared by writes, previews and publication staging. */
record RecordReferenceCopyState(
    Map<List<String>, RecordReferenceCopyStore.Stored> previous,
    List<RecordReferenceCopySnapshot> prospective) {
  RecordReferenceCopyState {
    previous = Map.copyOf(previous);
    prospective = List.copyOf(prospective);
  }

  /** Current target batches use the same exact-version checks without advancing any proof. */
  static RecordReferenceCopyState current(
      String parent,
      String tenantId,
      Map<String, List<RecordView>> rows,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    return capture(parent, tenantId, rows, rows, reader, 500);
  }

  static RecordReferenceCopyState capture(
      String parent,
      String tenantId,
      Map<String, List<RecordView>> before,
      Map<String, List<RecordView>> rows,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    return capture(parent, tenantId, before, rows, reader, 201);
  }

  private static RecordReferenceCopyState capture(
      String parent,
      String tenantId,
      Map<String, List<RecordView>> before,
      Map<String, List<RecordView>> rows,
      Function<RecordSelectPlan, CalciteQueryResult> reader,
      int limit) {
    if (rows.size() > 6 || rows.values().stream().mapToInt(List::size).sum() > limit) {
      throw RecordReferenceAdmission.incompatible();
    }
    Map<List<String>, RecordReferenceCopyStore.Stored> previous = new LinkedHashMap<>();
    List<RecordReferenceCopySnapshot> inputs = new ArrayList<>();
    int proofCount = 0;
    int bytes = 0;
    var store = new RecordReferenceCopyStore();
    for (var entry : rows.entrySet()) {
      Map<String, Long> versions = new LinkedHashMap<>();
      before
          .getOrDefault(entry.getKey(), List.of())
          .forEach(
              row -> {
                if (versions.putIfAbsent(row.id(), row.version()) != null) {
                  throw RecordReferenceAdmission.incompatible();
                }
              });
      for (int offset = 0; offset < entry.getValue().size(); offset += 100) {
        var batch =
            entry.getValue().subList(offset, Math.min(entry.getValue().size(), offset + 100));
        var keys =
            batch.stream()
                .map(row -> new RecordReferenceCopyStore.Key(tenantId, entry.getKey(), row.id()))
                .toList();
        var raw = reader.apply(store.lookupBatch(keys));
        for (var result : raw.rows()) {
          if (result.size() != 6 || !(result.get(5) instanceof String json)) {
            throw RecordReferenceAdmission.incompatible();
          }
          bytes += json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          if (bytes > 8 * 1024 * 1024) {
            throw RecordReferenceAdmission.incompatible();
          }
        }
        var stored = store.readBatch(keys, raw);
        for (var row : batch) {
          var old = stored.get(row.id());
          if (old == null) {
            continue;
          }
          if (!Long.valueOf(old.sourceVersion()).equals(versions.get(row.id()))) {
            throw RecordReferenceAdmission.incompatible();
          }
          proofCount += old.origins().size() + old.derivedOrigins().size();
          if (proofCount > 10050
              || previous.putIfAbsent(List.of(entry.getKey(), row.id()), old) != null) {
            throw RecordReferenceAdmission.incompatible();
          }
          if (!old.origins().isEmpty() || !old.derivedOrigins().isEmpty()) {
            inputs.add(
                new RecordReferenceCopySnapshot(
                    entry.getKey().equals(parent) ? null : entry.getKey(),
                    row.id(),
                    row.version(),
                    old.origins(),
                    old.derivedOrigins()));
          }
        }
      }
    }
    return new RecordReferenceCopyState(previous, inputs);
  }
}
