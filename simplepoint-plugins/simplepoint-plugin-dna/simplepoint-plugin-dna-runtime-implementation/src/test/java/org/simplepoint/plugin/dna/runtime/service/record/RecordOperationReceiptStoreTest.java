package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class RecordOperationReceiptStoreTest {
  private final RecordOperationReceiptStore store = new RecordOperationReceiptStore();
  private final RecordOperationIdentity operation =
      new RecordOperationIdentity(
          UUID.randomUUID().toString(),
          "tenant",
          "actor",
          "model",
          "operation-key",
          "a".repeat(64));

  @Test
  void parametersCarryTheEnvelopeWithoutEmbeddingKeysOrJsonIntoSql() {
    String body = "{\"label\":\"中文・日本語😀'\"}";
    var insert = store.insert(operation, Instant.EPOCH, body);
    assertThat(insert.sql()).doesNotContain(body, operation.key(), operation.id());
    assertThat(insert.parameters())
        .contains(body, operation.id(), operation.tenantId(), operation.actorId());
    assertThat(store.lookup(operation).parameters()).containsExactly(operation.id());
    assertThat(store.read(operation, result(List.of(row(body)), false, 1))).isEqualTo(body);
  }

  @Test
  void verifiesEveryIdentityBeforeReturningMalformedOrNegativePayloads() {
    for (int column = 0; column < 5; column++) {
      var row = new ArrayList<>(row("malformed-private-payload"));
      row.set(column, "foreign");
      assertThatThrownBy(() -> store.read(operation, result(List.of(row), false, 1)))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure -> {
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT);
                assertThat(failure.getMessage()).doesNotContain("foreign", "private");
              });
    }
  }

  @Test
  void rejectsTruncatedDuplicatedMismatchedCountsOrIncompleteRows() {
    var row = row("{}");
    for (var query :
        List.of(
            result(List.of(row), true, 1),
            result(List.of(row, row), false, 2),
            result(List.of(row), false, 0),
            result(List.of(), false, 1),
            result(List.of(row.subList(0, 5)), false, 1))) {
      assertThatThrownBy(() -> store.read(operation, query))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThat(store.read(operation, result(List.of(), false, 0))).isNull();
  }

  @Test
  void enforcesTheActualUtf8StorageBudgetForEveryPayloadCodec() {
    for (String body : List.of(" ", "a".repeat(65_536), "汉".repeat(21_846))) {
      assertThatThrownBy(() -> store.insert(operation, Instant.EPOCH, body))
          .isInstanceOf(DnaRuntimeException.class);
      assertThatThrownBy(() -> store.read(operation, result(List.of(row(body)), false, 1)))
          .isInstanceOf(DnaRuntimeException.class);
    }
    String exact = "汉".repeat(21_845);
    assertThat(store.read(operation, result(List.of(row(exact)), false, 1))).isEqualTo(exact);
  }

  private List<Object> row(String body) {
    return List.of(
        operation.tenantId(),
        operation.actorId(),
        operation.modelId(),
        operation.key(),
        operation.fingerprint(),
        body);
  }

  private static CalciteQueryResult result(List<List<Object>> rows, boolean truncated, long count) {
    return new CalciteQueryResult(List.of(), rows, truncated, count, 0, null);
  }
}
