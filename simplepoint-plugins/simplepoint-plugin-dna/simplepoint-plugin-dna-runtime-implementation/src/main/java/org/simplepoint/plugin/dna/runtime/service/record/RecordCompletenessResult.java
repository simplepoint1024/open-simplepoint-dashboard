package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView;

/** Strict decoding shared by observation and transactional publication inspections. */
final class RecordCompletenessResult {

  private RecordCompletenessResult() {
  }

  static RecordCompletenessView read(final RecordCompletenessQuery query, final CalciteQueryResult result) {
    try {
      if (result.truncated() || result.rows().size() != 1
          || result.rows().getFirst().size() != query.fields().size() + 1) {
        throw new IllegalArgumentException("Unexpected aggregate shape");
      }
      var row = result.rows().getFirst();
      long total = count(row.getFirst());
      Map<String, Long> empty = new LinkedHashMap<>();
      for (int index = 0; index < query.fields().size(); index++) {
        Object sum = row.get(index + 1);
        empty.put(query.fields().get(index), total == 0 && sum == null ? 0L : count(sum));
      }
      return new RecordCompletenessView(total, empty);
    } catch (IllegalArgumentException | ArithmeticException exception) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
          "Record completeness inspection returned invalid aggregates", exception);
    }
  }

  private static long count(final Object value) {
    if (!(value instanceof Number)) {
      throw new IllegalArgumentException("Missing aggregate");
    }
    return new BigDecimal(value.toString()).longValueExact();
  }

}
