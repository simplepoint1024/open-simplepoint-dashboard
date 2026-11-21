package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;

class RecordCollectionReceiptTest {
  @Test
  void roundTripsOneHundredEdgesWithAllEndpointVersionsInsteadOfTruncatingSqlResults() {
    var input =
        java.util.stream.IntStream.range(2, 102)
            .mapToObj(
                i -> new CollectionEdgeMutation(id(i), 0L, -1L, CollectionEdgeMutation.Mode.LINK))
            .toList();
    var result =
        result(
            1,
            java.util.stream.IntStream.range(2, 102)
                .mapToObj(i -> new CollectionRecordResult.Edge(id(i), 1L, 0L, true, true))
                .toList());
    var json = encode(result);
    assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(65_535);
    assertThat(RecordCollectionReceipt.decode(json).result()).isEqualTo(result);
    requireCommand(binding(false), command(0, input), result);
    assertThat(result.edges()).hasSize(100);
  }

  @Test
  void preservesAbsentEdgesWithoutInventingRowsOrUpdatingRoots() {
    var result = result(3, List.of(new CollectionRecordResult.Edge(id(2), 7L, -1L, false, false)));
    var command =
        command(
            3,
            List.of(
                new CollectionEdgeMutation(id(2), 7L, -1L, CollectionEdgeMutation.Mode.UNLINK)));
    requireCommand(
        binding(false), command, RecordCollectionReceipt.decode(encode(result)).result());
    assertThatThrownBy(() -> requireCommand(binding(false), command, result(4, result.edges())))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                requireCommand(
                    binding(false),
                    command,
                    result(
                        4, List.of(new CollectionRecordResult.Edge(id(2), 8L, 0L, false, true)))))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void selfRelationTouchesOneRootEvenIfItsOwnEdgeWasUnchanged() {
    var command =
        command(
            3,
            List.of(
                new CollectionEdgeMutation(id(1), 3L, 2L, CollectionEdgeMutation.Mode.LINK),
                new CollectionEdgeMutation(id(2), 0L, -1L, CollectionEdgeMutation.Mode.LINK)));
    var result =
        result(
            4,
            List.of(
                new CollectionRecordResult.Edge(id(1), 4L, 2L, true, false),
                new CollectionRecordResult.Edge(id(2), 1L, 0L, true, true)));
    requireCommand(binding(true), command, result);
    var stored =
        RecordCollectionReceipt.decode(
            RecordCollectionReceipt.encode(
                RecordCollectionReceipt.accepted(binding(true), result)));
    assertThat(stored.sourceModelId()).isEqualTo(stored.targetModelId());
    // Replay reads no current model, relation or target metadata.
    RecordCollectionReceipt.requireCommand(command, stored);
    assertThatThrownBy(() -> requireCommand(binding(false), command, result))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void malformedMissingDuplicateExtraOrImpreciseFieldsCannotBecomeValidReceipts() throws Exception {
    var json =
        encode(result(0, List.of(new CollectionRecordResult.Edge(id(2), 0L, -1L, false, false))));
    var mapper = new ObjectMapper();
    for (String key :
        List.of(
            "sourceVersion",
            "sourceRecordId",
            "sourceModelId",
            "targetModelId",
            "relationId",
            "edges",
            "contractVersion",
            "evaluatedAt")) {
      var value = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
      value.remove(key);
      assertThatThrownBy(() -> RecordCollectionReceipt.decode(value.toString()))
          .isInstanceOf(DnaRuntimeException.class);
    }
    for (String broken :
        List.of(
            json + " {}",
            json.replace("\"sourceVersion\":0", "\"sourceVersion\":0.0"),
            json.replace("\"sourceVersion\":0", "\"sourceVersion\":9223372036854775808"),
            json.replace("\"linked\":false", "\"linked\":null"),
            json.replace("\"linked\":false", "\"linked\":false,\"linked\":true"),
            json.replace("\"edgeVersion\":-1", "\"edgeVersion\":-2"),
            json.replace("\"contractVersion\":\"1\"", "\"contractVersion\":\"2\""),
            json.replace("\"changed\":false", "\"changed\":true"),
            json.replace("\"changed\":false", "\"changed\":false,\"extra\":true"))) {
      assertThatThrownBy(() -> RecordCollectionReceipt.decode(broken))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  @Test
  void rejectedRootTargetOrModeCannotBeReplacedByAnotherSuccessfulOutcome() {
    var command =
        command(
            0,
            List.of(new CollectionEdgeMutation(id(2), 0L, -1L, CollectionEdgeMutation.Mode.LINK)));
    for (var outcome :
        List.of(
            result(1, List.of(new CollectionRecordResult.Edge(id(3), 1L, 0L, true, true))),
            result(1, List.of(new CollectionRecordResult.Edge(id(2), 0L, 0L, true, true))),
            result(1, List.of(new CollectionRecordResult.Edge(id(2), 1L, 0L, false, true))))) {
      assertThatThrownBy(() -> requireCommand(binding(false), command, outcome))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  private static String encode(CollectionRecordResult result) {
    return RecordCollectionReceipt.encode(RecordCollectionReceipt.accepted(binding(false), result));
  }

  private static void requireCommand(
      RecordCollectionBinding binding,
      CollectionRecordCommand command,
      CollectionRecordResult result) {
    var frozen = RecordCollectionReceipt.accepted(binding, result);
    RecordCollectionReceipt.requireCommand(
        command, RecordCollectionReceipt.decode(RecordCollectionReceipt.encode(frozen)));
  }

  private static RecordCollectionBinding binding(boolean self) {
    return RecordCollectionBindingTest.binding(
        "projects", self ? "projects" : "members", "edges", "source_id", "target_id");
  }

  private static CollectionRecordResult result(
      long version, List<CollectionRecordResult.Edge> edges) {
    return new CollectionRecordResult("1", id(1), version, edges, Instant.EPOCH);
  }

  private static CollectionRecordCommand command(
      long sourceVersion, List<CollectionEdgeMutation> edges) {
    return new CollectionRecordCommand(
        "1",
        "projects",
        new RecordSchemaReference(1, "a".repeat(64)),
        "members",
        id(1),
        sourceVersion,
        edges,
        "collection",
        30_000);
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }
}
