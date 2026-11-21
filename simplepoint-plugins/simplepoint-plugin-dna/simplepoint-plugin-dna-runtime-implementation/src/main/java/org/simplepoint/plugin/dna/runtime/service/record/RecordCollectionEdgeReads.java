package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;

/** Strict internal decoding; truncated/foreign/retired-corrupt rows never become absent edges. */
final class RecordCollectionEdgeReads {
  private RecordCollectionEdgeReads() {}

  static Map<String, RecordCollectionEdgeState> read(
      RecordCollectionBinding binding,
      List<RecordCollectionEdgeKey> keys,
      CalciteQueryResult result) {
    RecordCollectionSql.requireKeys(binding, keys);
    if (result.truncated()
        || result.rows().size() > keys.size()
        || result.returnedRows() != result.rows().size()) {
      throw RecordReferenceAdmission.incompatible();
    }
    var expected = keys.stream().collect(Collectors.toMap(RecordCollectionEdgeKey::id, key -> key));
    var states = new LinkedHashMap<String, RecordCollectionEdgeState>();
    for (var row : result.rows()) {
      if (row.size() != 6
          || !(row.get(0) instanceof String id)
          || !(row.get(1) instanceof String tenant)
          || !(row.get(2) instanceof Number version)
          || row.get(3) != null
          || row.get(4) != null && !(row.get(4) instanceof String)
          || row.get(5) != null && !(row.get(5) instanceof String)) {
        throw RecordReferenceAdmission.incompatible();
      }
      var key = expected.get(id);
      if (key == null || states.containsKey(id)) {
        throw RecordReferenceAdmission.incompatible();
      }
      long revision;
      try {
        revision = new BigDecimal(version.toString()).longValueExact();
      } catch (ArithmeticException | NumberFormatException invalid) {
        throw RecordReferenceAdmission.incompatible();
      }
      states.put(
          id,
          RecordCollectionEdgeState.stored(
              key, id, tenant, revision, (String) row.get(4), (String) row.get(5)));
    }
    for (var key : keys) {
      states.putIfAbsent(key.id(), RecordCollectionEdgeState.absent());
    }
    return Map.copyOf(states);
  }
}
