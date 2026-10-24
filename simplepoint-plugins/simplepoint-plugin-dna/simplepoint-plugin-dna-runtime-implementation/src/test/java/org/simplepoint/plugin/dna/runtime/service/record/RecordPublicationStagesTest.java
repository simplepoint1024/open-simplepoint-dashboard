package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationStageFixtures.PARENT;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationStageFixtures.SECOND;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class RecordPublicationStagesTest {
  private final RecordPublicationStages store = new RecordPublicationStages();
  private final RecordPublicationIntent intent = RecordPublicationStageFixtures.intent();

  @Test
  void chunksLargeUnicodeWithoutLosingExactValuesAndAuditMetadata() {
    var aggregate = RecordPublicationStageFixtures.aggregate(PARENT, 65_536);
    var prepared = store.prepare(intent, aggregate);
    assertThat(prepared.header().payloadBytes()).isGreaterThan(2 * 1024 * 1024);
    var statements = statements(prepared);
    var headers = result(header(statements.getLast()));
    assertThat(store.read(intent, PARENT, headers)).isEqualTo(prepared.header());
    assertThat(store.readPage(intent, RecordPublicationStages.Phase.STAGED, null, 1, headers)).containsExactly(prepared.header());
    assertThat(store.payload(intent, prepared.header(), chunks(statements))).isEqualTo(aggregate);
    for (var statement : statements) {
      assertThat(statement.sql()).doesNotContain("tenant-a", "原值", "original-updater", PARENT);
      assertThat(statement.sql()).contains("CAST(? AS TIMESTAMP(6))");
    }
    assertThat(intent.bindings().values()).allMatch(binding -> binding.target().schemaName().isEmpty());
  }

  @Test
  void rejectsMissingSwappedDuplicatedCorruptedAndCrossTenantChunks() {
    var prepared = store.prepare(intent, RecordPublicationStageFixtures.aggregate(PARENT, 5000));
    var statements = statements(prepared);
    var original = chunks(statements);
    assertThat(prepared.header().chunkCount()).isGreaterThan(2);
    invalid(() -> store.payload(intent, prepared.header(), plan -> result()));
    var foreign = new RecordPublicationIntent(1, intent.key(), intent.command(), "f".repeat(64), intent.previousFingerprint(),
        intent.actorId(), intent.evaluatedAt(), intent.bindings(), null);
    invalid(() -> store.payload(foreign, prepared.header(), original));
    invalid(() -> store.payload(intent, prepared.header(), plan -> {
      var rows = new ArrayList<>(original.apply(plan).rows());
      Collections.swap(rows, 0, 1);
      return resultRows(rows);
    }));
    for (var change : List.of(List.of(0, "foreign"), List.of(1, "other-tenant"), List.of(2, "other-stage"),
        List.of(3, new BigDecimal("0.5")), List.of(4, "0".repeat(64)), List.of(5, "corrupted"))) {
      invalid(() -> store.payload(intent, prepared.header(), plan -> {
        var rows = new ArrayList<>(original.apply(plan).rows());
        var row = new ArrayList<>(rows.getFirst());
        row.set((Integer) change.getFirst(), change.get(1));
        rows.set(0, row);
        return resultRows(rows);
      }));
    }
    invalid(() -> store.payload(intent, new RecordPublicationStages.Header(prepared.header().intentChecksum(), PARENT, prepared.header().phase(), "0".repeat(64),
        prepared.header().payloadBytes(), prepared.header().chunkCount()), original));
    invalid(() -> store.payload(intent, prepared.header(), plan -> {
      var rows = new ArrayList<>(original.apply(plan).rows());
      rows.add(rows.getFirst());
      return resultRows(rows);
    }));
  }

  @Test
  void rejectsManifestIdentityTypePhaseAndPayloadSizeTampering() {
    var prepared = store.prepare(intent, RecordPublicationStageFixtures.aggregate(PARENT, 1));
    var row = header(statements(prepared).getLast());
    for (var change : List.of(List.of(0, "foreign"), List.of(1, "tenant-b"), List.of(2, "other-model"), List.of(3, 2),
        List.of(4, "other-publication"), List.of(5, "f".repeat(64)), List.of(6, "f".repeat(64)), List.of(7, SECOND),
        List.of(8, new BigDecimal("1.1")), List.of(8, "1"), List.of(9, "APPLIED"), List.of(10, "bad"),
        List.of(11, RecordPublicationPayload.MAX_BYTES + 1), List.of(12, 0))) {
      var broken = new ArrayList<>(row);
      broken.set((Integer) change.getFirst(), change.get(1));
      invalid(() -> store.read(intent, PARENT, result(broken)));
    }
    invalid(() -> store.read(intent, PARENT, resultRows(List.of(row, row))));
    invalid(() -> store.readPage(intent, RecordPublicationStages.Phase.APPLIED, null, 1, result(row)));
    invalid(() -> store.readPage(intent, RecordPublicationStages.Phase.STAGED, PARENT, 1, result(row)));
    assertThat(store.read(intent, PARENT, result())).isNull();
    assertThatThrownBy(() -> store.page(intent, RecordPublicationStages.Phase.STAGED, null, 101)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresOwnedFormulaFieldsAndExactlyOneParent() {
    var aggregate = RecordPublicationStageFixtures.aggregate(PARENT, 1);
    var row = aggregate.rows().getFirst();
    assertThatThrownBy(() -> new RecordPublicationAggregate(1, PARENT, List.of(row, row))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationAggregate(1, SECOND, aggregate.rows())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationAggregate(1, PARENT, List.of(aggregate.rows().getLast())))
        .isInstanceOf(IllegalArgumentException.class);
    for (String field : List.of("unowned", "created_by", "parent_record_id")) {
      var forged = RecordPublicationStageFixtures.row(row.modelId(), PARENT, null, Map.of(field, row.changes().get("result")));
      assertThatThrownBy(() -> store.prepare(intent, new RecordPublicationAggregate(1, PARENT, List.of(forged))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var foreign = RecordPublicationStageFixtures.row("foreign", PARENT, null, row.changes());
    assertThatThrownBy(() -> store.prepare(intent, new RecordPublicationAggregate(1, PARENT, List.of(foreign))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationAggregate.Change(new LogicalValue(LogicalValueKind.STRING, "1"),
        new LogicalValue(LogicalValueKind.INTEGER, "1"))).isInstanceOf(IllegalArgumentException.class);
    var touch = RecordPublicationStageFixtures.row(row.modelId(), PARENT, null, Map.of());
    store.prepare(intent, new RecordPublicationAggregate(1, PARENT, List.of(touch, aggregate.rows().getLast())));
  }

  @Test
  void strictCodecRejectsAmbiguousVersionsDuplicateFieldsAndTrailingData() {
    var aggregate = RecordPublicationStageFixtures.aggregate(PARENT, 1);
    String json = RecordPublicationPayload.encode(aggregate);
    assertThat(RecordPublicationPayload.ascii(json)).isTrue();
    for (String broken : List.of(json + " {}", "null", json.replace("\"contractVersion\":1", "\"contractVersion\":1.1"),
        json.replace("\"contractVersion\":1", "\"contractVersion\":\"1\""),
        json.replace("\"contractVersion\":1", "\"contractVersion\":1,\"contractVersion\":1"),
        json.replace("\"previousVersion\":19", "\"previousVersion\":19.1"),
        json.replace("\"previousVersion\":19", "\"previousVersion\":9223372036854775807"))) {
      invalid(() -> RecordPublicationPayload.decode(broken));
    }
    assertThat(RecordPublicationPayload.decode(json)).isEqualTo(aggregate);
    assertThatThrownBy(() -> aggregate.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> aggregate.rows().getFirst().changes().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void serializationStopsAtAggregateBudgetBeforeAnySqlCanBeProduced() {
    String previous = "汉".repeat(RecordPublicationPayload.MAX_BYTES / 6 + 1);
    var row = RecordPublicationStageFixtures.row(intent.key().modelId(), PARENT, null,
        Map.of("result", RecordPublicationStageFixtures.change(LogicalValueKind.STRING, previous, "new")));
    invalid(() -> store.prepare(intent, new RecordPublicationAggregate(1, PARENT, List.of(row))));
  }

  @Test
  void onlyForwardCasTransitionsBindOriginalIntentAndPayload() {
    var header = store.prepare(intent, RecordPublicationStageFixtures.aggregate(PARENT, 1)).header();
    var update = store.advance(intent, header, RecordPublicationStages.Phase.APPLIED, RecordPublicationFixtures.AT);
    assertThat(update.sql()).contains("\"intent_checksum\" = ?", "\"payload_checksum\" = ?", "\"payload_bytes\" = ?", "\"record_version\" = ?");
    assertThat(update.parameters()).contains(intent.key().tenantId(), intent.fingerprint(), header.payloadChecksum(),
        RecordPublicationStore.intentChecksum(intent));
    store.advance(intent, header.withPhase(RecordPublicationStages.Phase.APPLIED), RecordPublicationStages.Phase.RESTORED,
        RecordPublicationFixtures.AT);
    assertThatThrownBy(() -> store.advance(intent, header, RecordPublicationStages.Phase.RESTORED, RecordPublicationFixtures.AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.advance(intent, header.withPhase(RecordPublicationStages.Phase.RESTORED),
        RecordPublicationStages.Phase.STAGED, RecordPublicationFixtures.AT)).isInstanceOf(IllegalArgumentException.class);
  }

  private List<RecordSqlStatement> statements(RecordPublicationStages.Prepared prepared) {
    var result = new ArrayList<RecordSqlStatement>();
    store.inserts(prepared).forEach(result::add);
    return result;
  }

  private static List<Object> header(RecordSqlStatement statement) {
    var p = statement.parameters();
    return List.of(p.get(0), p.get(1), p.get(7), p.get(8), p.get(9), p.get(10), p.get(11), p.get(12), p.get(2), p.get(13),
        p.get(14), p.get(15), p.get(16));
  }

  private static Function<RecordSelectPlan, CalciteQueryResult> chunks(List<RecordSqlStatement> statements) {
    return plan -> {
      int start = ((Number) plan.parameters().get(2)).intValue(), end = ((Number) plan.parameters().get(3)).intValue();
      var rows = new ArrayList<List<Object>>();
      for (int i = start; i < end; i++) {
        var p = statements.get(i).parameters();
        rows.add(List.of(p.get(0), p.get(1), p.get(7), p.get(8), p.get(9), p.get(10)));
      }
      return resultRows(rows);
    };
  }

  private static CalciteQueryResult resultRows(List<List<Object>> rows) {
    return new CalciteQueryResult(List.of(), rows, false, rows.size(), 0, null);
  }

  private static CalciteQueryResult result(List<Object> row) {
    return resultRows(List.of(row));
  }

  private static CalciteQueryResult result() {
    return new CalciteQueryResult(List.of(), List.of(), false, 0, 0, null);
  }

  private static void invalid(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        error -> assertThat(error.errorCode()).isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
  }
}
