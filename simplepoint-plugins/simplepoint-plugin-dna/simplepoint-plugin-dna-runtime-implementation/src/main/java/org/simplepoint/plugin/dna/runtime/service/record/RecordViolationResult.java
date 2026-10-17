package org.simplepoint.plugin.dna.runtime.service.record;

import java.math.BigDecimal;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Strict aggregate decoding: an invalid count cannot be interpreted as a successful precheck. */
final class RecordViolationResult {

  private RecordViolationResult() {
  }

  static long read(final CalciteQueryResult result) {
    try {
      if (result.truncated() || result.rows().size() != 1 || result.rows().getFirst().size() != 1
          || !(result.rows().getFirst().getFirst() instanceof Number value)) {
        throw new IllegalArgumentException("Unexpected violation aggregate shape");
      }
      long count = new BigDecimal(value.toString()).longValueExact();
      if (count < 0) {
        throw new IllegalArgumentException("Negative violation count");
      }
      return count;
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
          "Record violation inspection returned an invalid aggregate", failure);
    }
  }
}
