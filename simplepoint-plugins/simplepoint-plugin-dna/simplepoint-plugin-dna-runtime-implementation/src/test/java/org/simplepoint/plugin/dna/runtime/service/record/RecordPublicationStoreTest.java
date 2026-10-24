package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationFixtures.AT;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationFixtures.KEY;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

class RecordPublicationStoreTest {
  private final RecordPublicationStore store = new RecordPublicationStore();

  @Test
  void retainsLegacyCanonicalIntentBytes() {
    assertThat(RecordPublicationStore.intentChecksum(RecordPublicationFixtures.intent(KEY))).isEqualTo("b09ae08f07e43bd7ced93c93cc49a93e9251325331b96807c9e4fa9ba3fa01cf");
  }

  @Test
  void preservesFrozenUnicodeIntentAndNeverInterpolatesValuesIntoSql() {
    var entry = RecordPublicationFixtures.entry(2);
    var insert = store.insert(entry);
    assertThat(insert.sql()).doesNotContain("中文", "CONCAT", "tenant-a", "publication-model");
    assertThat(store.read(KEY, 1, result(row(insert)))).isEqualTo(entry);
    assertThat(store.readActive(KEY, result(row(insert)))).isEqualTo(entry);
    assertThat(store.lookup(KEY, 1).parameters()).containsExactly(insert.recordId(), KEY.tenantId(), KEY.modelId(), 1L);
    assertThat(store.read(KEY, 1, result())).isNull();
    assertThat(store.active(KEY).maxRows()).isEqualTo(2);
    var update = store.advance(entry, entry.checkpoint().batch(1, "first"), AT.plusSeconds(1));
    assertThat(update.sql()).contains("\"intent_checksum\" = ?", "\"checkpoint_checksum\" = ?", "\"record_version\" = ?");
    assertThat(update.parameters()).contains(KEY.tenantId(), KEY.modelId(), entry.intent().fingerprint());
  }

  @Test
  void refusesOtherOwnersRevisionsFractionalCountersAndCorruptedRows() {
    var row = row(store.insert(RecordPublicationFixtures.entry(2)));
    invalid(() -> store.read(new RecordWriteFenceKey("tenant-b", KEY.modelId()), 1, result(row)));
    invalid(() -> store.read(new RecordWriteFenceKey(KEY.tenantId(), "other"), 1, result(row)));
    invalid(() -> store.read(KEY, 2, result(row)));
    for (var change : List.of(List.of(0, "other"), List.of(1, "other"), List.of(2, "other"),
        List.of(3, new BigDecimal("1.5")), List.of(4, "a".repeat(64)), List.of(5, new BigDecimal("1.5")),
        List.of(6, "COMMITTED"), List.of(7, "a".repeat(64)), List.of(9, "a".repeat(64)))) {
      var broken = new ArrayList<>(row);
      broken.set((Integer) change.getFirst(), change.get(1));
      invalid(() -> store.read(KEY, 1, result(broken)));
    }
    invalid(() -> store.read(KEY, 1, result(List.of("incomplete"))));
    invalid(() -> store.read(KEY, 1, result(row, row)));
    invalid(() -> store.readActive(KEY, result(row, row)));
  }

  @Test
  void rejectsMalformedOrNoncanonicalJsonEvenWithRecomputedChecksums() throws Exception {
    var row = row(store.insert(RecordPublicationFixtures.entry(2)));
    String original = (String) row.get(8);
    for (String json : List.of("null", original + " {}", original.replace("\"contractVersion\":1", "\"contractVersion\":1.5"),
        original.replace("\"contractVersion\":1", "\"contractVersion\":\"1\""),
        original.replace("\"actorId\":\"publisher\"", "\"actorId\":\"publisher\",\"actorId\":\"other\""),
        original.replace("\"contractVersion\":1", "\"contractVersion\":2"))) {
      invalidJson(row, 8, 7, json);
    }
    String checkpoint = (String) row.get(10);
    for (String json : List.of("null", checkpoint + " {}", checkpoint.replace("\"sequence\":1", "\"sequence\":1.5"),
        checkpoint.replace("\"staged\":0", "\"staged\":3"), checkpoint.replace("STAGING", "UNKNOWN"))) {
      invalidJson(row, 10, 9, json);
    }
  }

  @Test
  void freezesOwnershipAndRejectsInvalidSchemaBindingsClocksAndOriginalDefinitions() {
    var intent = RecordPublicationFixtures.intent(KEY);
    assertThatThrownBy(() -> intent.bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new RecordPublicationIntent(1, KEY, intent.command(), intent.fingerprint(),
        intent.previousFingerprint(), "publisher", AT.toString(), Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationIntent(1, KEY, intent.command(), intent.fingerprint(),
        intent.previousFingerprint(), "publisher", AT.plusNanos(1).toString(), intent.bindings(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationIntent(1, KEY, intent.command(), intent.fingerprint(),
        intent.previousFingerprint(), "publisher", AT.toString(), intent.bindings(),
        new RecordPublicationIntent.InstalledCalculation(1, "a".repeat(64))))
        .isInstanceOf(IllegalArgumentException.class);
    var initial = RecordPublicationFixtures.entry(2);
    assertThatThrownBy(() -> store.insert(new RecordPublicationStore.Entry(intent, initial.checkpoint().batch(1, "a"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ownedBindingsMustMatchTheCandidateAndOnePhysicalNamespace() {
    var original = RecordPublicationFixtures.intent(KEY);
    var command = original.command();
    var schema = RecordPublicationFixtures.SCHEMA;
    var owned = new RecordWritePolicyPublicationCommand(KEY.modelId(), command.appId(), command.formId(), schema,
        0, 1, command.publicationKey(), command.policyChecksum(), Map.of(), command.timeoutMs(), Map.of(),
        new RecordCalculationDefinition("1", command.calculation().formulas(),
            Map.of("lines", new RecordCalculationScope("detail-model", schema, Map.of()))));
    var child = new RecordPublicationIntent.Binding(schema, schema,
        new RuntimeMetadataTarget("source", "database", "forms", "lines"), 7);
    String fingerprint = new RecordRequestFingerprint(new com.fasterxml.jackson.databind.ObjectMapper()).of(owned);
    var bindings = new java.util.HashMap<>(original.bindings());
    bindings.put("detail-model", child);
    var frozen = new RecordPublicationIntent(1, KEY, owned, fingerprint, original.previousFingerprint(),
        original.actorId(), original.evaluatedAt(), bindings, null);
    bindings.clear();
    assertThat(frozen.bindings()).hasSize(2);
    for (var target : List.of(new RuntimeMetadataTarget("other", "database", "forms", "lines"),
        new RuntimeMetadataTarget("source", "other", "forms", "lines"),
        new RuntimeMetadataTarget("source", "database", "other", "lines"),
        new RuntimeMetadataTarget("source", "database", "forms", "records"))) {
      var invalidBindings = Map.of(KEY.modelId(), original.bindings().get(KEY.modelId()), "detail-model",
          new RecordPublicationIntent.Binding(schema, schema, target, 7));
      assertThatThrownBy(() -> new RecordPublicationIntent(1, KEY, owned, fingerprint, original.previousFingerprint(),
          original.actorId(), original.evaluatedAt(), invalidBindings, null)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new RecordPublicationIntent(1, KEY, owned, fingerprint, original.previousFingerprint(),
        original.actorId(), original.evaluatedAt(), original.bindings(), null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsExplicitResourcesWithoutChangingVersionOneBytes() throws Exception {
    var legacy = RecordPublicationFixtures.intent(KEY);
    var old = legacy.bindings().get(KEY.modelId());
    var bindings = Map.of(KEY.modelId(), new RecordPublicationIntent.Binding(old.activeSchema(), old.requestedSchema(), old.target(), 0));
    var associations = new java.util.LinkedHashMap<String, RecordPublicationIntent.Resource>();
    associations.put("page-b", new RecordPublicationIntent.Resource("binding-b",
        new RecordPublicationIntent.Projection("projection-b", "app:b", 9, KEY.modelId())));
    associations.put("page-a", new RecordPublicationIntent.Resource("binding-a", null));
    Map<String, Map<String, RecordPublicationIntent.Resource>> resources = Map.of(KEY.modelId(), associations);
    var intent = new RecordPublicationIntent(2, KEY, legacy.command(), legacy.fingerprint(), legacy.previousFingerprint(), legacy.actorId(),
        legacy.evaluatedAt(), bindings, null, resources);
    associations.clear();
    assertThat(intent.resources().get(KEY.modelId())).hasSize(2);
    assertThatThrownBy(() -> intent.resources().get(KEY.modelId()).clear()).isInstanceOf(UnsupportedOperationException.class);
    var entry = new RecordPublicationStore.Entry(intent, RecordPublicationCheckpoint.start(1));
    var stored = row(store.insert(entry));
    assertThat(store.read(KEY, 1, result(stored))).isEqualTo(entry);
    var json = (String) stored.get(8);
    assertThat(json).contains("\"resources\":", "binding-a", "projection-b");
    invalidJson(stored, 8, 7, json.replace("\"generation\":9", "\"generation\":9.5"));
    invalidJson(stored, 8, 7, json.replace("\"contractVersion\":2", "\"contractVersion\":1"));
    assertThat(RecordPublicationStore.intentChecksum(legacy)).isEqualTo("b09ae08f07e43bd7ced93c93cc49a93e9251325331b96807c9e4fa9ba3fa01cf");
  }

  @Test
  void rejectsMixedUnidentifiedOrWronglyOwnedResourceSnapshots() {
    var legacy = RecordPublicationFixtures.intent(KEY);
    var old = legacy.bindings().get(KEY.modelId());
    var binding = new RecordPublicationIntent.Binding(old.activeSchema(), old.requestedSchema(), old.target(), 0);
    var resource = new RecordPublicationIntent.Resource("binding", new RecordPublicationIntent.Projection("projection", "owner", 2, KEY.modelId()));
    var wrongOwner = new RecordPublicationIntent.Resource("binding", new RecordPublicationIntent.Projection("projection", "owner", 2, "other-model"));
    for (var resources : List.of(Map.<String, Map<String, RecordPublicationIntent.Resource>>of(),
        Map.of(KEY.modelId(), Map.of("page", wrongOwner)), Map.of(KEY.modelId(), Map.of(" ", resource)),
        Map.of(KEY.modelId(), Map.of("page-a", resource, "page-b", resource)))) {
      assertThatThrownBy(() -> new RecordPublicationIntent(2, KEY, legacy.command(), legacy.fingerprint(), legacy.previousFingerprint(),
          legacy.actorId(), legacy.evaluatedAt(), Map.of(KEY.modelId(), binding), null, resources)).isInstanceOf(IllegalArgumentException.class);
    }
    var resources = Map.of(KEY.modelId(), Map.of("page", resource));
    assertThatThrownBy(() -> new RecordPublicationIntent(1, KEY, legacy.command(), legacy.fingerprint(), legacy.previousFingerprint(),
        legacy.actorId(), legacy.evaluatedAt(), legacy.bindings(), null, resources)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationIntent(2, KEY, legacy.command(), legacy.fingerprint(), legacy.previousFingerprint(),
        legacy.actorId(), legacy.evaluatedAt(), Map.of(KEY.modelId(), new RecordPublicationIntent.Binding(
            old.activeSchema(), old.requestedSchema(), old.target(), 7)), null, resources)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationIntent.Projection("projection", null, 1, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationIntent.Projection("projection", "owner", 0, KEY.modelId())).isInstanceOf(IllegalArgumentException.class);
  }

  private void invalidJson(List<Object> row, int jsonColumn, int checksumColumn, String json) throws Exception {
    var corrupted = new ArrayList<>(row);
    corrupted.set(jsonColumn, json);
    corrupted.set(checksumColumn, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))));
    invalid(() -> store.read(KEY, 1, result(corrupted)));
  }

  private static List<Object> row(RecordSqlStatement statement) {
    var p = statement.parameters();
    return List.of(p.get(0), p.get(1), p.get(7), p.get(8), p.get(9), p.get(2), p.get(10), p.get(11), p.get(12), p.get(13), p.get(14));
  }

  @SafeVarargs
  private static CalciteQueryResult result(List<Object>... rows) {
    return new CalciteQueryResult(List.of(), List.of(rows), false, rows.length, 0, null);
  }

  private static void invalid(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DnaRuntimeException.class,
        failure -> assertThat(failure.errorCode()).isEqualTo(DnaRuntimeErrorCode.PERSISTENCE_FAILED));
  }
}
