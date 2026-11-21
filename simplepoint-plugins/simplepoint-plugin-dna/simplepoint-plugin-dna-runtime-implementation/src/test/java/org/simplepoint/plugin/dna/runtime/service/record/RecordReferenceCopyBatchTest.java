package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class RecordReferenceCopyBatchTest {
  private final RecordReferenceCopyStore store = new RecordReferenceCopyStore();
  private final RecordReferenceCopyStore.Key first =
      new RecordReferenceCopyStore.Key("tenant", "model", "11111111-1111-1111-1111-111111111111");
  private final RecordReferenceCopyStore.Key second =
      new RecordReferenceCopyStore.Key("tenant", "model", "22222222-2222-2222-2222-222222222222");

  @Test
  void unorderedPartialResultsRemainBoundToTheirExactRequestedRecordAndTenant() {
    var keys = List.of(first, second);
    var plan = store.lookupBatch(keys);
    assertThat(plan.parameters()).containsExactly("tenant", "model", first.id(), second.id());
    assertThat(plan.maxRows()).isEqualTo(2);
    var result = store.readBatch(keys, result(List.of(row(second, 3), row(first, 1)), false));
    assertThat(result.get(first.recordId()).sourceVersion()).isEqualTo(1);
    assertThat(result.get(second.recordId()).sourceVersion()).isEqualTo(3);
    assertThat(store.readBatch(keys, result(List.of(row(first, 1)), false)))
        .containsOnlyKeys(first.recordId());
    assertThat(store.readBatch(keys, result(List.of(), false))).isEmpty();
    assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void mixedTenantsModelsDuplicatesAndOversizedRequestsNeverProduceQueries() {
    for (var keys :
        List.of(
            List.<RecordReferenceCopyStore.Key>of(),
            List.of(first, first),
            List.of(first, new RecordReferenceCopyStore.Key("foreign", "model", second.recordId())),
            List.of(
                first, new RecordReferenceCopyStore.Key("tenant", "foreign", second.recordId())),
            IntStream.range(0, 101)
                .mapToObj(
                    i ->
                        new RecordReferenceCopyStore.Key(
                            "tenant", "model", UUID.randomUUID().toString()))
                .toList())) {
      assertThatThrownBy(() -> store.lookupBatch(keys)).isInstanceOf(DnaRuntimeException.class);
      assertThatThrownBy(() -> store.readBatch(keys, result(List.of(), false)))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void corruptedPhysicalIdentityOrScopeCannotReassignOriginsBetweenRequestedRows() {
    for (int column = 0; column < 4; column++) {
      var row = new ArrayList<>(row(first, 1));
      row.set(column, column == 0 ? second.id() : column == 3 ? second.recordId() : "foreign");
      assertThatThrownBy(() -> store.readBatch(List.of(first, second), result(List.of(row), false)))
          .isInstanceOf(DnaRuntimeException.class);
    }
    var row = row(first, 1);
    assertThatThrownBy(
            () -> store.readBatch(List.of(first, second), result(List.of(row, row), false)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> store.readBatch(List.of(first), result(List.of(row), true)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> store.readBatch(List.of(second), result(List.of(row), false)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private List<Object> row(RecordReferenceCopyStore.Key key, long version) {
    var json =
        store
            .write(
                key,
                "actor",
                null,
                version,
                List.of(RecordReferenceCopyStoreTest.origin()),
                Instant.EPOCH)
            .parameters()
            .getLast();
    return List.of(key.id(), key.tenantId(), key.modelId(), key.recordId(), version, json);
  }

  private static CalciteQueryResult result(List<List<Object>> rows, boolean truncated) {
    return new CalciteQueryResult(List.of(), rows, truncated, rows.size(), 0, null);
  }
}
