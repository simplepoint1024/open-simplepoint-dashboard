package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class RecordCollectionEdgeReadsTest {
  private final RecordCollectionBinding binding =
      RecordCollectionBindingTest.binding("projects", "members", "edges", "source_id", "target_id");

  @Test
  void distinguishesAbsentRetiredAndActiveEdgesInOneBoundedRead() {
    var keys = List.of(key(2), key(3), key(4));
    var rows = result(List.of(row(keys.get(1), 7L, false), row(keys.getFirst(), 2L, true)), false);
    var states = RecordCollectionEdgeReads.read(binding, keys, rows);
    assertThat(states.get(keys.getFirst().id())).isEqualTo(new RecordCollectionEdgeState(2, true));
    assertThat(states.get(keys.get(1).id())).isEqualTo(new RecordCollectionEdgeState(7, false));
    assertThat(states.get(keys.get(2).id())).isEqualTo(RecordCollectionEdgeState.absent());
    var plan = RecordCollectionSql.lookup(binding, keys);
    assertThat(plan.parameters())
        .containsExactly("tenant", keys.get(0).id(), keys.get(1).id(), keys.get(2).id());
    assertThat(plan.maxRows()).isEqualTo(3);
    assertThat(plan.physicalRelationalExecution()).isTrue();
  }

  @Test
  void refusesTruncationDuplicateForeignRowsHalfEdgesAndNonIntegralVersions() {
    var key = key(2);
    var good = row(key, 0L, true);
    var malformed = new ArrayList<List<Object>>();
    for (int index : List.of(0, 1, 3, 4, 5)) {
      var row = new ArrayList<>(good);
      row.set(index, index == 4 ? null : "foreign");
      malformed.add(row);
    }
    malformed.add(row(key, new BigDecimal("1.5"), true));
    malformed.add(row(key, Double.NaN, true));
    malformed.add(row(key, Long.MAX_VALUE, true));
    for (var row : malformed) {
      assertThatThrownBy(
              () ->
                  RecordCollectionEdgeReads.read(
                      binding, List.of(key), result(List.of(row), false)))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThatThrownBy(
            () ->
                RecordCollectionEdgeReads.read(binding, List.of(key), result(List.of(good), true)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                RecordCollectionEdgeReads.read(
                    binding, List.of(key, key(3)), result(List.of(good, good), false)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                RecordCollectionEdgeReads.read(
                    binding,
                    List.of(key),
                    new CalciteQueryResult(List.of(), List.of(good), false, 0, 0, null)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void boundsQueriesToOneTenantAndDistinctVerifiedIdentities() {
    var keys = java.util.stream.IntStream.range(2, 103).mapToObj(this::key).toList();
    assertThat(RecordCollectionSql.lookup(binding, keys.subList(0, 100)).maxRows()).isEqualTo(100);
    for (var invalid :
        List.of(
            keys,
            List.of(key(2), key(2)),
            List.<RecordCollectionEdgeKey>of(),
            List.of(
                key(2),
                new RecordCollectionEdgeKey(
                    key(3).id(), "other", key(3).sourceId(), key(3).targetId())))) {
      assertThatThrownBy(() -> RecordCollectionSql.lookup(binding, invalid))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  private RecordCollectionEdgeKey key(int target) {
    return RecordCollectionEdgeKey.of(
        binding, "tenant", new UUID(0, 1).toString(), new UUID(0, target).toString());
  }

  private static List<Object> row(RecordCollectionEdgeKey key, Number version, boolean active) {
    return Arrays.asList(
        key.id(),
        key.tenantId(),
        version,
        null,
        active ? key.sourceId() : null,
        active ? key.targetId() : null);
  }

  private static CalciteQueryResult result(List<List<Object>> rows, boolean truncated) {
    return new CalciteQueryResult(List.of(), rows, truncated, rows.size(), 0, null);
  }
}
