package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionRecordCommandTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void canonicalizesIncrementalTargetsAndRoundTrips() throws Exception {
    var input = new java.util.ArrayList<>(List.of(edge(3), edge(2)));
    var command = command(input);
    input.clear();
    assertThat(command.mutations())
        .extracting(CollectionEdgeMutation::targetRecordId)
        .containsExactly(id(2), id(3));
    assertThat(mapper.readValue(mapper.writeValueAsString(command), CollectionRecordCommand.class))
        .isEqualTo(command);
    assertThat(command).isEqualTo(command(List.of(edge(2), edge(3))));
  }

  @Test
  void omittedOrNullVersionCannotImpersonateAnObservationOfVersionZero() throws Exception {
    for (String name :
        List.of("expectedSourceVersion", "expectedTargetVersion", "expectedEdgeVersion")) {
      for (boolean explicitNull : List.of(false, true)) {
        var json = mapper.valueToTree(command(List.of(edge(2))));
        var object =
            (com.fasterxml.jackson.databind.node.ObjectNode)
                (name.equals("expectedSourceVersion") ? json : json.get("mutations").get(0));
        if (explicitNull) {
          object.putNull(name);
        } else {
          object.remove(name);
        }
        assertThatThrownBy(() -> mapper.treeToValue(json, CollectionRecordCommand.class))
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void rejectsAmbiguousOrUnboundedCollectionsAndInvalidEdgeGenerations() {
    for (var input :
        List.of(
            List.<CollectionEdgeMutation>of(),
            List.of(edge(2), edge(2)),
            java.util.stream.IntStream.range(2, 103).mapToObj(this::edge).toList())) {
      assertThatThrownBy(() -> command(input)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            command(java.util.stream.IntStream.range(2, 102).mapToObj(this::edge).toList())
                .mutations())
        .hasSize(100);
    for (long version : new long[] {-2, Long.MAX_VALUE}) {
      assertThatThrownBy(
              () ->
                  new CollectionEdgeMutation(id(2), 0L, version, CollectionEdgeMutation.Mode.LINK))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () -> new CollectionEdgeMutation(id(2), -1L, -1L, CollectionEdgeMutation.Mode.LINK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CollectionEdgeMutation("1-1-1-1-1", 0L, -1L, CollectionEdgeMutation.Mode.LINK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectionEdgeMutation(id(2), 0L, -1L, null))
        .isInstanceOf(NullPointerException.class);
  }

  private CollectionRecordCommand command(List<CollectionEdgeMutation> edges) {
    return new CollectionRecordCommand(
        "1",
        "projects",
        new RecordSchemaReference(1, "a".repeat(64)),
        "members",
        id(1),
        0L,
        edges,
        "collection-command",
        30_000);
  }

  private CollectionEdgeMutation edge(int target) {
    return new CollectionEdgeMutation(id(target), 0L, -1L, CollectionEdgeMutation.Mode.LINK);
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }
}
