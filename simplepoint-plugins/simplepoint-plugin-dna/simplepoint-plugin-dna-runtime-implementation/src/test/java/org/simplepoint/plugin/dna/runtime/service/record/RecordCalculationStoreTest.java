package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

class RecordCalculationStoreTest {
  private static final RecordWriteFenceKey KEY = new RecordWriteFenceKey("tenant-a", "model-a");
  private static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");
  private final RecordCalculationStore store = new RecordCalculationStore();

  @Test
  void storesImmutableIdentityAndBindsDefinitionsAsParameters() {
    var definition = policy("CONCAT(\"中文・日本語😀\",[label])");
    var statement = store.insert(KEY, 3, "publisher", AT, definition);
    assertThat(statement.sql()).doesNotContain("中文", "CONCAT", "tenant-a");
    assertThat(store.read(KEY, 3, result(row(statement))).policy()).isEqualTo(definition);
    assertThat(store.read(KEY, 5, result(row(statement))).revision()).isEqualTo(3);
    assertThat(store.read(KEY, 2, result())).isNull();
    assertThat(store.lookup(KEY, 3).parameters()).containsExactly("tenant-a", "model-a", 3L);
    assertThat(store.lookup(KEY, 3).sql()).contains("ORDER BY \"policy_revision\" DESC", "FETCH NEXT 1 ROWS ONLY");
  }

  @Test
  void refusesCrossTenantCrossModelFutureOrInexactRevisionAndCorruptedPayloads() {
    var row = row(store.insert(KEY, 3, "publisher", AT, policy("[price]*[quantity]")));
    assertInvalid(() -> store.read(new RecordWriteFenceKey("tenant-b", KEY.modelId()), 3, result(row)));
    assertInvalid(() -> store.read(new RecordWriteFenceKey(KEY.tenantId(), "other-model"), 3, result(row)));
    assertInvalid(() -> store.read(KEY, 2, result(row)));
    for (var change : List.of(
        List.of(0, "wrong-id"), List.of(1, "other-tenant"), List.of(2, "other-model"),
        List.of(3, new BigDecimal("3.5")), List.of(3, -1L), List.of(4, "f".repeat(64)),
        List.of(5, "{}"), List.of(5, "中".repeat(512 * 1024)))) {
      var corrupted = new ArrayList<>(row);
      corrupted.set((Integer) change.getFirst(), change.get(1));
      assertInvalid(() -> store.read(KEY, 3, result(corrupted)));
    }
  }

  @Test
  void refusesInvalidResultShapesAndNullRevision() {
    var row = row(store.insert(KEY, 1, "publisher", AT, policy("1")));
    assertInvalid(() -> store.read(KEY, 1, result(row, row)));
    assertInvalid(() -> store.read(KEY, 1, result(List.of("incomplete"))));
    var corrupt = new ArrayList<>(row);
    corrupt.set(3, null);
    assertInvalid(() -> store.read(KEY, 1, result(corrupt)));
    assertInvalid(() -> store.read(KEY, -1, result()));
  }

  @Test
  void requiresExplicitDefinitionAndSeparatesVersionsAndTenants() {
    assertThatThrownBy(() -> store.insert(KEY, 0, "publisher", AT, policy("1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.insert(KEY, 1, "publisher", AT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.lookup(KEY, -1)).isInstanceOf(IllegalArgumentException.class);
    var first = store.insert(KEY, 1, "publisher", AT, policy("1"));
    assertThat(first.recordId()).isEqualTo(store.insert(KEY, 1, "other-publisher", AT, policy("2")).recordId());
    assertThat(first.recordId()).isNotEqualTo(store.insert(KEY, 2, "publisher", AT, policy("1")).recordId());
    assertThat(first.recordId()).isNotEqualTo(store.insert(new RecordWriteFenceKey("tenant-b", KEY.modelId()),
        1, "publisher", AT, policy("1")).recordId());
  }

  @Test
  void rejectsInvalidStoredContractsEvenWhenTheByteChecksumMatches() throws Exception {
    var row = row(store.insert(KEY, 1, "publisher", AT, policy("1")));
    String original = (String) row.get(5);
    for (String json : List.of("null", original.replace("\"contractVersion\":1", "\"contractVersion\":2"),
        original.replace("\"appId\":\"app\"", "\"appId\":\"app\",\"appId\":\"other\""),
        original.replace("\"languageVersion\":\"1\"", "\"languageVersion\":\"unknown\""))) {
      var corrupted = new ArrayList<>(row);
      corrupted.set(5, json);
      corrupted.set(4, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      assertInvalid(() -> store.read(KEY, 1, result(corrupted)));
    }
  }

  private static RecordCalculationPolicy policy(String expression) {
    return new RecordCalculationPolicy(1, "app", "form", new RecordSchemaReference(1, "a".repeat(64)),
        new RecordCalculationDefinition("1", Map.of("total", expression), Map.of()));
  }

  private static List<Object> row(RecordSqlStatement statement) {
    var values = statement.parameters();
    return List.of(values.get(0), values.get(1), values.get(7), values.get(8), values.get(9), values.get(10));
  }

  @SafeVarargs
  private static CalciteQueryResult result(List<Object>... rows) {
    return new CalciteQueryResult(List.of(), List.of(rows), false, rows.length, 0, null);
  }

  private static void assertInvalid(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
  }
}
