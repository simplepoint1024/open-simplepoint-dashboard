package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

class RecordSnapshotStoreTest {
  private final RecordSnapshotStore store = new RecordSnapshotStore();
  private final RecordOperationIdentity operation =
      new RecordOperationIdentity("snapshot-id", "tenant", "actor", "model", "key", "a".repeat(64));
  private final OwnedRecordView values =
      new OwnedRecordView(
          new RecordView("record", 0, Map.of("amount", "9007199254740993.01")), Map.of());

  @Test
  void preservesExactLegacyValuesWithoutInventingCalculationProvenance() {
    var snapshot =
        store.read(
            operation,
            result(
                "{\"parent\":{\"id\":\"record\",\"version\":0,\"values\":{"
                    + "\"amount\":\"9007199254740993.01\"}},\"details\":{}}"));
    assertThat(snapshot.values()).isEqualTo(values);
    assertThat(snapshot.calculationRevision()).isZero();
    assertThat(snapshot.calculationChecksum()).isNull();
  }

  @Test
  void keepsOrdinarySnapshotsReadableByThePreviousServiceVersion() throws Exception {
    var snapshot = RecordStoredSnapshot.capture(values, null);
    String json = (String) store.insert(operation, snapshot, Instant.EPOCH).parameters().getLast();
    assertThat(json)
        .doesNotContain("contractVersion", "calculationRevision", "calculationChecksum");
    assertThat(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, OwnedRecordView.class))
        .isEqualTo(values);
    assertThat(store.read(operation, result(json))).isEqualTo(snapshot);
  }

  @Test
  void roundTripsVersionedProvenanceAndRejectsInvalidEnvelopes() {
    var snapshot = new RecordStoredSnapshot(2, 7, "b".repeat(64), values);
    var statement = store.insert(operation, snapshot, Instant.EPOCH);
    String json = (String) statement.parameters().getLast();
    assertThat(store.read(operation, result(json))).isEqualTo(snapshot);
    for (String invalid :
        List.of(
            json.replace("\"contractVersion\":2", "\"contractVersion\":3"),
            json.replace("\"contractVersion\":2", "\"contractVersion\":2.5"),
            json.replace("\"calculationRevision\":7", "\"calculationRevision\":7.5"),
            json.replace("\"calculationRevision\":7", "\"calculationRevision\":0.5"),
            json.replace("\"calculationRevision\":7", "\"calculationRevision\":null"),
            json.replace("\"calculationRevision\":7", "\"calculationRevision\":0"),
            json.replace(
                "\"calculationRevision\":7", "\"calculationRevision\":7,\"calculationRevision\":0"),
            json.replace("\"calculationRevision\":7,", ""),
            json + " {}")) {
      assertThatThrownBy(() -> store.read(operation, result(invalid)))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void copiedSnapshotsKeepProtectedProvenanceEvenWithoutAnInstalledFormula() {
    String id = "11111111-1111-1111-1111-111111111111";
    var source =
        new OwnedRecordView(new RecordView(id, 0, Map.of("address", "old address")), Map.of());
    var origins =
        List.of(
            new RecordReferenceCopySnapshot(
                null, id, 0, List.of(RecordReferenceCopyStoreTest.origin())));
    var snapshot = RecordStoredSnapshot.capture(source, null).withCopyOrigins(origins);
    String json = (String) store.insert(operation, snapshot, Instant.EPOCH).parameters().getLast();
    assertThat(json).contains("\"contractVersion\":3", "\"copyOrigins\"");
    assertThat(store.read(operation, result(json))).isEqualTo(snapshot);
    for (String invalid :
        List.of(
            json.replace("\"contractVersion\":3", "\"contractVersion\":2"),
            json.replace("\"sourceVersion\":0", "\"sourceVersion\":1"),
            json.replace("\"sourceVersion\":0,", ""),
            json.replace("\"targetVersion\":3,", ""),
            json.replace("\"detailModelId\":null", "\"detailModelId\":\"foreign\""),
            json.replace("\"detailModelId\":null,", ""),
            json.replace(
                "\"sourceRecordId\":\"" + id + "\"",
                "\"sourceRecordId\":\"22222222-2222-2222-2222-222222222222\""))) {
      assertThatThrownBy(() -> store.read(operation, result(invalid)))
          .as(invalid)
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThatThrownBy(() -> new RecordStoredSnapshot(3, 0, null, source, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RecordStoredSnapshot(
                    3, 0, null, source, List.of(origins.getFirst(), origins.getFirst())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesWrongIdentityMalformedRowsAndOversizedJson() {
    var snapshot = new RecordStoredSnapshot(2, 0, null, values);
    String json = (String) store.insert(operation, snapshot, Instant.EPOCH).parameters().getLast();
    var wrong = new ArrayList<>(result(json).rows().getFirst());
    wrong.set(1, "other-actor");
    assertThatThrownBy(() -> store.read(operation, rows(List.of(wrong))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> store.read(operation, rows(List.of(List.of("tenant")))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> store.read(operation, rows(List.of(wrong, wrong))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> store.read(operation, result(" ".repeat(2 * 1024 * 1024 + 1))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private CalciteQueryResult result(String json) {
    return rows(List.of(List.of("tenant", "actor", "model", "key", "a".repeat(64), json)));
  }

  private CalciteQueryResult rows(List<List<Object>> rows) {
    return new CalciteQueryResult(List.of(), rows, false, rows.size(), 0, null);
  }
}
