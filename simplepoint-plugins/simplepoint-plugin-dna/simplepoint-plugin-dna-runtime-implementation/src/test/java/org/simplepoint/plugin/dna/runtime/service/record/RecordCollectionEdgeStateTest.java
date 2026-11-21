package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.LINK;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.UNLINK;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class RecordCollectionEdgeStateTest {
  private static final String SOURCE = "00000000-0000-0000-0000-000000000001";
  private static final String TARGET = "00000000-0000-0000-0000-000000000002";

  @Test
  void unlinkRetainsItsVersionAndDelayedLinkCannotReviveAnEdge() {
    var absent = RecordCollectionEdgeState.absent();
    assertThat(absent.transition(-1, UNLINK)).isSameAs(absent);
    var linked = absent.transition(-1, LINK);
    assertThat(linked).isEqualTo(new RecordCollectionEdgeState(0, true));
    assertThat(linked.transition(0, LINK)).isSameAs(linked);
    var retired = linked.transition(0, UNLINK);
    assertThat(retired).isEqualTo(new RecordCollectionEdgeState(1, false));
    assertThat(retired.transition(1, UNLINK)).isSameAs(retired);
    for (long obsolete : new long[] {-1, 0}) {
      assertThatThrownBy(() -> retired.transition(obsolete, LINK))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThat(retired.transition(1, LINK)).isEqualTo(new RecordCollectionEdgeState(2, true));
  }

  @Test
  void stableIdentityIgnoresStorageNamesButSeparatesTenantRolesAndModels() {
    var binding =
        RecordCollectionBindingTest.binding(
            "projects", "members", "edges", "source_id", "target_id");
    var key = RecordCollectionEdgeKey.of(binding, "tenant", SOURCE, TARGET);
    assertThat(key.id()).isEqualTo("199debe8-9233-8b9d-9f6a-adc13f8fbd9b");
    var renamed =
        RecordCollectionBindingTest.binding(
            "projects", "members", "renamed", "left_id", "right_id");
    assertThat(RecordCollectionEdgeKey.of(renamed, "tenant", SOURCE, TARGET)).isEqualTo(key);
    assertThat(RecordCollectionEdgeKey.of(binding, "other", SOURCE, TARGET).id())
        .isNotEqualTo(key.id());
    assertThat(RecordCollectionEdgeKey.of(binding, "tenant", TARGET, SOURCE).id())
        .isNotEqualTo(key.id());
    assertThat(
            RecordCollectionEdgeKey.of(
                    RecordCollectionBindingTest.binding(
                        "projects", "employees", "edges", "source_id", "target_id"),
                    "tenant",
                    SOURCE,
                    TARGET)
                .id())
        .isNotEqualTo(key.id());
    assertThat(java.util.UUID.fromString(key.id()).version()).isEqualTo(8);
    assertThat(java.util.UUID.fromString(key.id()).variant()).isEqualTo(2);
  }

  @Test
  void rejectsHalfEdgesAndMismatchedPersistentIdentities() {
    var binding =
        RecordCollectionBindingTest.binding(
            "projects", "members", "edges", "source_id", "target_id");
    var key = RecordCollectionEdgeKey.of(binding, "tenant", SOURCE, TARGET);
    assertThat(RecordCollectionEdgeState.stored(key, key.id(), "tenant", 1, null, null))
        .isEqualTo(new RecordCollectionEdgeState(1, false));
    assertThat(
            RecordCollectionEdgeState.stored(key, key.id(), "tenant", 0, SOURCE, TARGET).active())
        .isTrue();
    assertThatThrownBy(
            () -> RecordCollectionEdgeState.stored(key, key.id(), "tenant", 0, SOURCE, null))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> RecordCollectionEdgeState.stored(key, key.id(), "tenant", 0, null, TARGET))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> RecordCollectionEdgeState.stored(key, key.id(), "other", 0, SOURCE, TARGET))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> RecordCollectionEdgeState.stored(key, SOURCE, "tenant", 0, SOURCE, TARGET))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () -> RecordCollectionEdgeState.stored(key, key.id(), "tenant", 0, TARGET, SOURCE))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                new RecordCollectionEdgeState(Long.MAX_VALUE - 1, false)
                    .transition(Long.MAX_VALUE - 1, LINK))
        .isInstanceOf(DnaRuntimeException.class);
  }
}
