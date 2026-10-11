package org.simplepoint.plugin.dna.runtime.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;

class DnaRuntimeAuditEventTest {

  @Test
  void enforcesOutcomeAndNonNegativeOperationalMetrics() {
    DnaRuntimeAuditEvent event = event(
        DnaRuntimeAuditOutcome.SUCCEEDED,
        null,
        null,
        12
    );
    assertThat(event.dataSourceId()).isEqualTo("source-a");

    assertThatThrownBy(() -> event(
        DnaRuntimeAuditOutcome.FAILED,
        null,
        null,
        12
    )).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> event(
        DnaRuntimeAuditOutcome.FAILED,
        DnaRuntimeErrorCode.PERSISTENCE_FAILED,
        -1L,
        12
    )).isInstanceOf(IllegalArgumentException.class);
  }

  private static DnaRuntimeAuditEvent event(
      final DnaRuntimeAuditOutcome outcome,
      final DnaRuntimeErrorCode errorCode,
      final Long affectedRows,
      final long durationMillis
  ) {
    return new DnaRuntimeAuditEvent(
        "event-a",
        DnaRuntimeAuditEventType.PHYSICAL_MODEL_REGISTERED,
        Instant.parse("2026-09-29T00:00:00Z"),
        "tenant-a",
        "user-a",
        "model-a",
        "app-a",
        "form-a",
        "source-a",
        1,
        affectedRows,
        durationMillis,
        outcome,
        errorCode
    );
  }
}
