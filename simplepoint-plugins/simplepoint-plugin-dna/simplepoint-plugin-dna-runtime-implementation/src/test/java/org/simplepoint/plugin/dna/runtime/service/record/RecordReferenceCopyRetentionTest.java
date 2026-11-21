package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

class RecordReferenceCopyRetentionTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";
  private final RecordReferenceCopyStore.Key key =
      new RecordReferenceCopyStore.Key("tenant", "model", ROW);
  private final RecordCalculationStore.Installed installed =
      new RecordCalculationStore.Installed(
          1,
          new RecordCalculationPolicy(
              1,
              "app",
              "form",
              new RecordSchemaReference(1, "a".repeat(64)),
              new RecordCalculationDefinition("1", Map.of(), Map.of()),
              Map.of("model", Set.of("address"))));

  @Test
  void ordinaryAndRepeatedEditsRetainOriginsAtTheFinalBusinessVersion() {
    var source = List.of(update(4), update(5));
    var write = new RecordWritePreparation(source, null);
    var reads = new AtomicInteger();
    var effects =
        RecordReferenceCopyRetention.prepare(
            write,
            installed,
            query -> {
              reads.incrementAndGet();
              assertThat(query.parameters()).contains("tenant", "model", key.id());
              return result(3, false);
            });
    assertThat(reads).hasValue(1);
    assertThat(effects)
        .singleElement()
        .satisfies(
            effect -> {
              assertThat(effect.sourceVersion()).isEqualTo(5);
              assertThat(effect.previous().sourceVersion()).isEqualTo(3);
              assertThat(effect.origins()).containsExactly(RecordReferenceCopyStoreTest.origin());
              effect.verify(source);
            });
  }

  @Test
  void emptyLedgersAdvanceButStaleVersionsAndNonSequentialWritesFail() {
    var retained =
        RecordReferenceCopyRetention.prepare(
            new RecordWritePreparation(List.of(update(4)), null),
            installed,
            query -> result(3, true));
    assertThat(retained)
        .singleElement()
        .satisfies(
            effect -> {
              assertThat(effect.origins()).isEmpty();
              assertThat(effect.sourceVersion()).isEqualTo(4);
            });
    for (var changes : List.of(List.of(update(5)), List.of(update(4), update(6)))) {
      assertThatThrownBy(
              () ->
                  RecordReferenceCopyRetention.prepare(
                      new RecordWritePreparation(changes, null),
                      installed,
                      query -> result(3, false)))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void explicitRecalculationWinsAndLegacyPoliciesDoNotReadLedgers() {
    var clear =
        new RecordReferenceCopyMutation(
            key,
            new RecordReferenceCopyStore.Stored(3, List.of(RecordReferenceCopyStoreTest.origin())),
            4,
            List.of(),
            List.of());
    var write =
        new RecordWritePreparation(List.of(update(4)), null, 1, List.of(), null, List.of(clear));
    assertThat(
            RecordReferenceCopyRetention.prepare(
                write,
                installed,
                query -> {
                  throw new AssertionError("Explicit atomic effect must not be overwritten");
                }))
        .containsExactly(clear);
    assertThat(
            RecordReferenceCopyRetention.prepare(
                new RecordWritePreparation(List.of(update(4)), null),
                null,
                query -> {
                  throw new AssertionError("Legacy policy has no protected records");
                }))
        .isEmpty();
  }

  private RecordSqlStatement update(long version) {
    return new RecordSqlStatement(
        "business",
        List.of(),
        RecordMutationType.UPDATE,
        ROW,
        version,
        Instant.EPOCH,
        new RecordSqlStatement.Subject("model", "tenant", "actor"));
  }

  private CalciteQueryResult result(long version, boolean empty) {
    String json =
        (String)
            new RecordReferenceCopyStore()
                .write(
                    key,
                    "actor",
                    null,
                    version,
                    empty ? List.of() : List.of(RecordReferenceCopyStoreTest.origin()),
                    Instant.EPOCH)
                .parameters()
                .getLast();
    return new CalciteQueryResult(
        List.of(),
        List.of(List.of(key.id(), "tenant", "model", ROW, version, json)),
        false,
        1,
        0,
        null);
  }
}
