package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

class RecordReadSessionTest {
  private static final RecordWriteFenceKey KEY = new RecordWriteFenceKey("tenant", "model");
  private static final OwnedRecordView VALUES = new OwnedRecordView(new RecordView("record", 0, Map.of()), Map.of());
  private static final RecordCalculationPolicy POLICY = new RecordCalculationPolicy(1, "app", "form",
      new RecordSchemaReference(1, "a".repeat(64)), new RecordCalculationDefinition("1", Map.of("total", "[secret]"), Map.of()));

  @Test
  void historicalResolutionRequiresTheExactCapturedRevisionAndChecksum() {
    var snapshot = RecordStoredSnapshot.capture(VALUES, new RecordCalculationStore.Installed(2, POLICY));
    var session = new RecordReadSession(KEY, 4, null, plan -> result(2));
    assertThat(session.historical(snapshot)).isEqualTo(new RecordCalculationStore.Installed(2, POLICY));
    invalid(() -> session.historical(new RecordStoredSnapshot(2, 2, "b".repeat(64), VALUES)));
    invalid(() -> new RecordReadSession(KEY, 4, null, plan -> result(1)).historical(snapshot));
    invalid(() -> new RecordReadSession(KEY, 4, null, plan -> new CalciteQueryResult(List.of(), List.of(), false, 0, 0, null))
        .historical(snapshot));
  }

  @Test
  void futureProvenanceIsRejectedAndLegacyReadsDoNotInventAFormula() {
    var session = new RecordReadSession(KEY, 1, null, plan -> {throw new AssertionError("No metadata lookup is permitted");});
    invalid(() -> session.historical(RecordStoredSnapshot.capture(VALUES, new RecordCalculationStore.Installed(2, POLICY))));
    assertThat(session.historical(RecordStoredSnapshot.capture(VALUES, null))).isNull();
  }

  @Test
  void crossTenantAndCrossModelOperationsAreRejectedBeforeAnyQuery() {
    var session = new RecordReadSession(KEY, 1, null, plan -> {throw new AssertionError("No cross-scope query is permitted");});
    for (var operation : List.of(new RecordOperationIdentity("id", "other", "actor", "model", "key", "a".repeat(64)),
        new RecordOperationIdentity("id", "tenant", "actor", "other", "key", "a".repeat(64)))) {
      invalid(() -> session.snapshot(operation));
      invalid(() -> session.receipt(operation));
    }
  }

  private static CalciteQueryResult result(long revision) {
    var values = new RecordCalculationStore().insert(KEY, revision, "publisher", Instant.EPOCH, POLICY).parameters();
    return new CalciteQueryResult(List.of(), List.of(List.of(values.get(0), values.get(1), values.get(7),
        values.get(8), values.get(9), values.get(10))), false, 1, 0, null);
  }

  private static void invalid(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
  }
}
