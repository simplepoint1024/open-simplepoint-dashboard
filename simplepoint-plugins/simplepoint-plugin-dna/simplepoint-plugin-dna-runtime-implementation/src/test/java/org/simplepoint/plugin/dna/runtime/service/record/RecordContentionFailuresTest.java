package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.security.access.AccessDeniedException;

class RecordContentionFailuresTest {
  @ParameterizedTest
  @CsvSource({
    "postgresql,40001,0",
    "postgresql,40P01,0",
    "postgresql,55P03,0",
    "mysql,40001,1213",
    "mysql,HY000,1205",
    "mysql,40001,1205"
  })
  void recognizesOnlySupportedVendorDiagnostics(String dialect, String state, int code) {
    var sql = new SQLException("Opaque diagnostic", state, code);
    assertThat(
            RecordContentionFailures.retryable(
                dialect,
                new DnaRuntimeException(
                    DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED, "Wrapped", sql)))
        .isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "postgresql,57014,0",
    "postgresql,40003,0",
    "postgresql,08006,0",
    "postgresql,23505,0",
    "mysql,40001,0",
    "mysql,HY000,0",
    "mysql,HY000,1213",
    "mysql,23000,1452",
    "h2,40001,0"
  })
  void neverTreatsCancellationUncertaintyIntegrityOrUnknownDialectAsContention(
      String dialect, String state, int code) {
    assertThat(
            RecordContentionFailures.retryable(dialect, new SQLException("deadlock", state, code)))
        .isFalse();
  }

  @Test
  void checksCauseAndNextChainsButRejectsConflictingDiagnosticsAndBusinessFailures() {
    var deadlock = new SQLException("Opaque", "40P01");
    var outer = new SQLException("Wrapper");
    outer.setNextException(deadlock);
    assertThat(RecordContentionFailures.retryable("postgresql", outer)).isTrue();
    deadlock.setNextException(new SQLException("Connection lost", "08006"));
    assertThat(RecordContentionFailures.retryable("postgresql", outer)).isFalse();
    assertThat(
            RecordContentionFailures.retryable(
                "postgresql",
                new DnaRuntimeException(
                    DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT,
                    "Version",
                    new SQLException("Opaque", "40P01"))))
        .isFalse();
    assertThat(
            RecordContentionFailures.retryable(
                "postgresql",
                new AccessDeniedException("Denied", new SQLException("Opaque", "40P01"))))
        .isFalse();
    var unrelated = new IllegalStateException("Not contention");
    unrelated.addSuppressed(new SQLException("Cleanup", "40P01"));
    assertThat(RecordContentionFailures.retryable("postgresql", unrelated)).isFalse();
  }

  @Test
  void boundsDiagnosticGraphsAndTerminatesCycles() {
    var sql = new SQLException("Opaque", "40P01");
    var wrapper = new RuntimeException(sql);
    sql.initCause(wrapper);
    assertThat(RecordContentionFailures.retryable("postgresql", wrapper)).isTrue();
    Throwable deep = new SQLException("Opaque", "40P01");
    for (int index = 0; index < 33; index++) {
      deep = new RuntimeException(deep);
    }
    assertThat(RecordContentionFailures.retryable("postgresql", deep)).isFalse();
  }
}
