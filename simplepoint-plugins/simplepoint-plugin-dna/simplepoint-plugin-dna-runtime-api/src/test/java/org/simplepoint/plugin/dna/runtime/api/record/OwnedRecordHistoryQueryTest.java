package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OwnedRecordHistoryQueryTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";

  @Test
  void transportPreservesExactIntentAndDeadlineChangesDoNotRewriteTheOperation() throws Exception {
    var query = query(command(true));
    var mapper = new ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsBytes(query), OwnedRecordHistoryQuery.class))
        .isEqualTo(query);
    assertThat(query.withTimeout(7).command()).isSameAs(query.command());
    assertThat(query.withTimeout(7).operationActorId()).isEqualTo("submitter");
    assertThatThrownBy(() -> query.withTimeout(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> query.withTimeout(30001)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingVersionOrTimeoutCannotBecomeAnImplicitZeroOrDefault() throws Exception {
    var mapper = new ObjectMapper();
    for (String field : List.of("recordVersion", "timeoutMs")) {
      var tree = mapper.valueToTree(query(command(true)));
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove(field);
      assertThatThrownBy(() -> mapper.treeToValue(tree, OwnedRecordHistoryQuery.class))
          .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
  }

  @Test
  void uncapturedCommandsAndMismatchedMappedIntentsAreRejected() {
    assertThatThrownBy(() -> query(command(false))).isInstanceOf(IllegalArgumentException.class);
    var mapped = new MappedOwnedRecordCommand("1", command(true), null, List.of());
    var different =
        new OwnedRecordCommand(
            new RecordTransactionCommand(
                "model",
                new RecordSchemaReference(1, "a".repeat(64)),
                List.of(new RecordCreateMutation("parent", Map.of("name", "different"))),
                30000,
                "snapshot",
                1),
            List.of(),
            new OwnedRecordSnapshotRequest(List.of()));
    assertThatThrownBy(
            () ->
                new OwnedRecordHistoryQuery(
                    "1", "submitter", "app", "form", "resource", ROW, 0, different, null, mapped))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static OwnedRecordHistoryQuery query(OwnedRecordCommand command) {
    return new OwnedRecordHistoryQuery(
        "1", "submitter", "app", "form", "resource", ROW, 0, command, null, null);
  }

  private static OwnedRecordCommand command(boolean captured) {
    return new OwnedRecordCommand(
        new RecordTransactionCommand(
            "model",
            new RecordSchemaReference(1, "a".repeat(64)),
            List.of(new RecordCreateMutation("parent", Map.of("name", "value"))),
            30000,
            "snapshot",
            1),
        List.of(),
        captured ? new OwnedRecordSnapshotRequest(List.of()) : null);
  }
}
