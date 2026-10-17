package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class RecordViolationResultTest {

  @Test
  void acceptsExactCountsAndRejectsMalformedAggregatesInsteadOfPublishing() {
    assertThat(RecordViolationResult.read(result(List.of(List.of(0L)), false))).isZero();
    assertThat(RecordViolationResult.read(result(List.of(List.of(new BigDecimal("9223372036854775807"))), false)))
        .isEqualTo(Long.MAX_VALUE);
    List<CalciteQueryResult> malformed = List.of(
        result(List.of(), false), result(List.of(List.of(0), List.of(0)), false), result(List.of(List.of(0, 0)), false),
        result(List.of(List.of(0)), true), result(List.of(java.util.Collections.singletonList(null)), false),
        result(List.of(List.of(-1)), false), result(List.of(List.of("0")), false),
        result(List.of(List.of(new BigDecimal("0.5"))), false),
        result(List.of(List.of(new BigDecimal("9223372036854775808"))), false));
    malformed.forEach(value -> assertThatThrownBy(() -> RecordViolationResult.read(value))
        .isInstanceOfSatisfying(DnaRuntimeException.class, failure ->
            assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED)));
  }

  private static CalciteQueryResult result(final List<List<Object>> rows, final boolean truncated) {
    return new CalciteQueryResult(List.of(), rows, truncated, rows.size(), 0, null);
  }
}
