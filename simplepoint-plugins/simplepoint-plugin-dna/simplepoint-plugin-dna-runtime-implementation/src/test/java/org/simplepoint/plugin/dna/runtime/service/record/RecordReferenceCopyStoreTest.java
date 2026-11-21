package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;

class RecordReferenceCopyStoreTest {
  private final RecordReferenceCopyStore store = new RecordReferenceCopyStore();
  private final RecordReferenceCopyStore.Key key =
      new RecordReferenceCopyStore.Key("tenant", "model", "11111111-1111-1111-1111-111111111111");

  @Test
  void parameterizedLedgerRoundTripsProtectedOriginsAndKeepsEmptyClearState() {
    var write = store.write(key, "actor", null, 7, List.of(origin()), Instant.EPOCH);
    assertThat(write.sql()).doesNotContain("customers-page", "'tenant'");
    String json = (String) write.parameters().getLast();
    var current = store.read(key, result(7, json));
    assertThat(current).isEqualTo(new RecordReferenceCopyStore.Stored(7, List.of(origin())));
    var cleared = store.write(key, "actor", current, 8, List.of(), Instant.EPOCH);
    assertThat(cleared.parameters())
        .contains(key.id(), key.tenantId(), key.modelId(), key.recordId(), 7L);
    assertThat(store.read(key, result(8, (String) cleared.parameters().get(3))).origins())
        .isEmpty();
  }

  @Test
  void immutableOriginSetsAreCanonicalRegardlessOfProviderIterationOrder() {
    var first = origin();
    var other =
        new RecordReferenceCopyOrigin(
            first.relationId(),
            "name-copy",
            first.mode(),
            "name",
            first.mappingRevision(),
            first.targetModelId(),
            first.targetAppId(),
            first.targetFormId(),
            first.targetResourceCode(),
            first.targetRecordId(),
            first.targetVersion(),
            "name",
            requirements("name"));
    var left = store.write(key, "actor", null, 0, List.of(first, other), Instant.EPOCH);
    var right = store.write(key, "actor", null, 0, List.of(other, first), Instant.EPOCH);
    assertThat(left.parameters().getLast()).isEqualTo(right.parameters().getLast());
  }

  @Test
  void identityDomainsAndTenantPredicatesDoNotCollideOrBorrowAnotherRecord() {
    assertThat(key.id())
        .isNotEqualTo(
            new RecordReferenceCopyStore.Key("tenant-other", key.modelId(), key.recordId()).id());
    assertThat(key.id())
        .isNotEqualTo(
            RecordRequestFingerprint.deterministicRecordId(
                key.tenantId(), key.modelId(), key.recordId()));
    var query = store.lookup(key);
    assertThat(query.parameters())
        .containsExactly(key.id(), key.tenantId(), key.modelId(), key.recordId());
    String json =
        (String)
            store
                .write(key, "actor", null, 0, List.of(origin()), Instant.EPOCH)
                .parameters()
                .getLast();
    for (int index = 0; index < 3; index++) {
      var row = new ArrayList<>(result(0, json).rows().getFirst());
      row.set(index, "foreign");
      assertThatThrownBy(() -> store.read(key, rows(List.of(row), false)))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void storedOriginsRequireExactVersionedJsonAndBoundedUniqueDestinations() {
    String json =
        (String)
            store
                .write(key, "actor", null, 0, List.of(origin()), Instant.EPOCH)
                .parameters()
                .getLast();
    for (String invalid :
        List.of(
            json.replace("\"contractVersion\":1", "\"contractVersion\":2"),
            json.replace("\"contractVersion\":1", "\"contractVersion\":1.5"),
            json.replace("\"contractVersion\":1", "\"contractVersion\":1,\"contractVersion\":1"),
            json.replace("\"targetVersion\":3", "\"targetVersion\":null"),
            json.replace("\"targetVersion\":3,", ""),
            json + " {}",
            " ".repeat(RecordReferenceCopyStore.MAX_BYTES + 1))) {
      assertThatThrownBy(() -> store.read(key, result(0, invalid)))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThatThrownBy(
            () -> store.write(key, "actor", null, 0, List.of(origin(), origin()), Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                store.write(
                    key,
                    "actor",
                    new RecordReferenceCopyStore.Stored(2, List.of(origin())),
                    2,
                    List.of(),
                    Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> store.read(key, rows(result(0, json).rows(), true)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                store.read(
                    key,
                    rows(
                        List.of(
                            result(0, json).rows().getFirst(), result(0, json).rows().getFirst()),
                        false)))
        .isInstanceOf(DnaRuntimeException.class);
  }

  static RecordReferenceCopyOrigin origin() {
    return new RecordReferenceCopyOrigin(
        "customer",
        "address-copy",
        org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping.Mode.SERVER_SNAPSHOT,
        "address",
        "a".repeat(64),
        "customer-model",
        "app",
        "customers",
        "customers-page",
        "22222222-2222-2222-2222-222222222222",
        3,
        "address",
        requirements("address"));
  }

  private static List<org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement>
      requirements(String field) {
    return List.of(
        new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement(
            "customer-model",
            "app",
            "customers",
            "customers-page",
            "22222222-2222-2222-2222-222222222222",
            null,
            null,
            List.of(field)));
  }

  private CalciteQueryResult result(long version, String json) {
    return rows(
        List.of(List.of(key.tenantId(), key.modelId(), key.recordId(), version, json)), false);
  }

  private static CalciteQueryResult rows(List<List<Object>> values, boolean truncated) {
    return new CalciteQueryResult(List.of(), values, truncated, values.size(), 0, null);
  }
}
