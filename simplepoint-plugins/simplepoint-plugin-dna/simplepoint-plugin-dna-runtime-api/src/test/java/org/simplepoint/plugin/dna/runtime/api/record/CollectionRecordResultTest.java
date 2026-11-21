package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionRecordResultTest {
  @Test
  void freezesCanonicalResultsAndPreservesUnchangedTombstoneVersions() {
    var edges = new ArrayList<>(List.of(edge(3, 4), edge(2, -1)));
    var result = new CollectionRecordResult("1", id(1), 7L, edges, Instant.EPOCH);
    edges.clear();
    assertThat(result.edges())
        .extracting(CollectionRecordResult.Edge::targetRecordId)
        .containsExactly(id(2), id(3));
    assertThat(result.edges())
        .extracting(CollectionRecordResult.Edge::edgeVersion)
        .containsExactly(-1L, 4L);
    assertThatThrownBy(() -> result.edges().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsMissingObservationsContradictoryStatesDuplicatesAndUnboundedResults() {
    for (var edges :
        List.of(
            List.<CollectionRecordResult.Edge>of(),
            List.of(edge(2, 0), edge(2, 0)),
            java.util.stream.IntStream.range(2, 103).mapToObj(i -> edge(i, 0)).toList())) {
      assertThatThrownBy(() -> new CollectionRecordResult("1", id(1), 0L, edges, Instant.EPOCH))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> new CollectionRecordResult("1", id(1), null, List.of(edge(2, 0)), Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectionRecordResult.Edge(id(2), null, 0L, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectionRecordResult.Edge(id(2), 0L, null, false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectionRecordResult.Edge(id(2), 0L, -1L, true, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectionRecordResult.Edge(id(2), 0L, -1L, false, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectionRecordResult.Edge(id(2), 0L, 0L, null, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CollectionRecordResult.Edge edge(int id, long version) {
    return new CollectionRecordResult.Edge(id(id), 7L, version, false, false);
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }
}
