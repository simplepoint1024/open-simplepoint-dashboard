package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class RecordPublicationCopyChangeTest {
  @Test
  void originImagesUseAnExplicitVersionAndPreserveLegacyCanonicalPayloads() {
    var fixture = new RecordPublicationPatchFixture(null, "public");
    String legacy = RecordPublicationPayload.encode(fixture.aggregate);
    assertThat(legacy).doesNotContain("copyChange");
    assertThat(RecordPublicationPayload.decode(legacy)).isEqualTo(fixture.aggregate);
    var source = fixture.aggregate.rows().getFirst();
    var before =
        new RecordReferenceCopyStore.Stored(0, List.of(RecordReferenceCopyStoreTest.origin()));
    var after = new RecordReferenceCopyStore.Stored(1, before.origins());
    var row =
        new RecordPublicationAggregate.Row(
            source.modelId(),
            source.recordId(),
            null,
            0,
            source.previousUpdatedBy(),
            source.previousUpdatedAt(),
            Map.of(),
            new RecordPublicationCopyChange(before, after));
    var aggregate = new RecordPublicationAggregate(2, source.recordId(), List.of(row));
    var intent = fixture.intent.withQueryProtection(Map.of(source.modelId(), Set.of("address")));
    aggregate.requireIntent(intent);
    var encoded = RecordPublicationPayload.encode(aggregate);
    assertThat(RecordPublicationPayload.decode(encoded)).isEqualTo(aggregate);
    assertThat(
            fixture.compiler.compile(
                intent, aggregate, fixture.models, RecordPublicationPatchCompiler.Direction.APPLY))
        .hasSize(1);
    assertThatThrownBy(() -> new RecordPublicationAggregate(1, source.recordId(), List.of(row)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordPublicationAggregate(2, source.recordId(), List.of(source)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> aggregate.requireIntent(fixture.intent))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RecordPublicationPayload.decode(
                    encoded.replace("\"sourceVersion\":1", "\"sourceVersion\":2")))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void restorationRequiresExactAppliedProofAndNormalWritesCannotReverseVersions() {
    var key =
        new RecordReferenceCopyStore.Key("tenant", "model", RecordPublicationPatchFixture.PARENT);
    var store = new RecordReferenceCopyStore();
    var before = new RecordReferenceCopyStore.Stored(3, List.of());
    var after =
        new RecordReferenceCopyStore.Stored(4, List.of(RecordReferenceCopyStoreTest.origin()));
    var restore = store.restorePublication(key, "publisher", after, before, Instant.EPOCH);
    assertThat(restore.sql()).contains("UPDATE", "\"origins_json\" = ?", "\"record_version\" = ?");
    assertThat(restore.parameters()).contains(key.id(), "tenant", "model", key.recordId(), 3L, 4L);
    assertThat(store.restorePublication(key, "publisher", after, null, Instant.EPOCH).sql())
        .startsWith("DELETE FROM");
    assertThatThrownBy(() -> store.write(key, "publisher", after, 3, List.of(), Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                store.restorePublication(
                    key,
                    "publisher",
                    after,
                    new RecordReferenceCopyStore.Stored(2, List.of()),
                    Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
  }
}
