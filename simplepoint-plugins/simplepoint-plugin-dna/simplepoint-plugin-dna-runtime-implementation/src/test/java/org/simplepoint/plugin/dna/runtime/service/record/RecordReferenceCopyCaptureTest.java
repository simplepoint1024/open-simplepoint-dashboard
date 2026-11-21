package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

class RecordReferenceCopyCaptureTest {
  private final RecordReferenceCopyCapture capture = new RecordReferenceCopyCapture();
  private final RecordOperationIdentity operation =
      new RecordOperationIdentity("snapshot", "tenant", "actor", "parent", "key", "a".repeat(64));
  private final RecordView parent =
      new RecordView(UUID.randomUUID().toString(), 2, Map.of("address", "retained"));

  @Test
  void capturesParentAndRetainedChildOriginsWithoutDependingOnCurrentMappingDeclarations() {
    var child = new RecordView(UUID.randomUUID().toString(), 5, Map.of());
    var value = snapshot(List.of(child));
    List<RecordSelectPlan> plans = new ArrayList<>();
    var result =
        capture.capture(
            operation,
            value,
            plan -> {
              plans.add(plan);
              return rows(
                  List.of(
                      ledger(
                          plan.parameters().get(1).toString(),
                          plan.parameters().get(1).equals("parent") ? parent : child)));
            });
    assertThat(result.contractVersion()).isEqualTo(3);
    assertThat(result.values()).isSameAs(value.values());
    assertThat(result.copyOrigins())
        .containsExactly(
            new RecordReferenceCopySnapshot(
                null,
                parent.id(),
                parent.version(),
                List.of(RecordReferenceCopyStoreTest.origin())),
            new RecordReferenceCopySnapshot(
                "child",
                child.id(),
                child.version(),
                List.of(RecordReferenceCopyStoreTest.origin())));
    assertThat(plans)
        .hasSize(2)
        .allSatisfy(plan -> assertThat(plan.parameters().getFirst()).isEqualTo("tenant"));
  }

  @Test
  void batchesRowsRatherThanFieldsAndKeepsLegacySnapshotsUnchangedWhenNoOriginsExist() {
    var children =
        IntStream.range(0, 200)
            .mapToObj(i -> new RecordView(UUID.randomUUID().toString(), 0, Map.of()))
            .toList();
    var value = snapshot(children);
    List<Integer> sizes = new ArrayList<>();
    assertThat(
            capture.capture(
                operation,
                value,
                plan -> {
                  sizes.add(plan.maxRows());
                  return rows(List.of());
                }))
        .isSameAs(value);
    assertThat(sizes).containsExactly(1, 100, 100);
    var tooMany =
        snapshot(
            IntStream.range(0, 201)
                .mapToObj(i -> new RecordView(UUID.randomUUID().toString(), 0, Map.of()))
                .toList());
    assertThatThrownBy(
            () ->
                capture.capture(
                    operation,
                    tooMany,
                    plan -> {
                      throw new AssertionError("No unbounded read");
                    }))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void staleOriginsCannotBeAttachedToNewerOrForeignSnapshots() {
    var value = snapshot(List.of());
    var stale = new RecordView(parent.id(), 1, Map.of());
    assertThatThrownBy(
            () -> capture.capture(operation, value, plan -> rows(List.of(ledger("parent", stale)))))
        .isInstanceOf(DnaRuntimeException.class);
    var foreign = new RecordView(UUID.randomUUID().toString(), 2, Map.of());
    assertThatThrownBy(
            () ->
                capture.capture(operation, value, plan -> rows(List.of(ledger("parent", foreign)))))
        .isInstanceOf(DnaRuntimeException.class);
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                capture.capture(
                    operation,
                    RecordStoredSnapshot.capture(
                        new OwnedRecordView(parent, Map.of("parent", List.of(parent))), null),
                    plan -> {
                      calls.incrementAndGet();
                      return rows(List.of());
                    }))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(calls).hasValue(0);
  }

  private RecordStoredSnapshot snapshot(List<RecordView> children) {
    return RecordStoredSnapshot.capture(
        new OwnedRecordView(parent, Map.of("child", children)), null);
  }

  private List<Object> ledger(String model, RecordView row) {
    var store = new RecordReferenceCopyStore();
    var key = new RecordReferenceCopyStore.Key("tenant", model, row.id());
    Object json =
        store
            .write(
                key,
                "actor",
                null,
                row.version(),
                List.of(RecordReferenceCopyStoreTest.origin()),
                Instant.EPOCH)
            .parameters()
            .getLast();
    return List.of(key.id(), "tenant", model, row.id(), row.version(), json);
  }

  private static CalciteQueryResult rows(List<List<Object>> rows) {
    return new CalciteQueryResult(List.of(), rows, false, rows.size(), 0, null);
  }
}
