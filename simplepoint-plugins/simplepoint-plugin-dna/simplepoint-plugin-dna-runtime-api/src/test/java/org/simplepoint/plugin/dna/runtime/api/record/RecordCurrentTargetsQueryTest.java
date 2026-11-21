package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordCurrentTargetsQueryTest {
  private final RecordSchemaReference schema = new RecordSchemaReference(1, "a".repeat(64));
  private final RecordPageQuery page =
      new RecordPageQuery("model", schema, 0, 50, List.of("name"), null, List.of());

  @Test
  void transportsExactlyOneSelectionWithoutAddingDerivedProperties() throws Exception {
    var mapper = new ObjectMapper();
    var record =
        new OwnedRecordQuery(
            new RecordLookupQuery(
                "model", schema, "11111111-1111-1111-1111-111111111111", List.of("name")),
            List.of());
    var related =
        new RelatedRecordPageQuery(
            page, "relation", "target", "22222222-2222-2222-2222-222222222222");
    for (var query :
        List.of(
            new RecordCurrentTargetsQuery("1", record, null, null, 30000),
            new RecordCurrentTargetsQuery("1", null, page, null, 30000),
            new RecordCurrentTargetsQuery("1", null, null, related, 30000))) {
      var json = mapper.writeValueAsBytes(query);
      assertThat(mapper.readValue(json, RecordCurrentTargetsQuery.class)).isEqualTo(query);
      assertThat(query.physicalModelId()).isEqualTo("model");
      assertThat(query.schemaReference()).isEqualTo(schema);
      assertThat(query.withTimeout(1).page()).isEqualTo(query.page());
      var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
      assertThat(tree.has("physicalModelId")).isFalse();
      tree.remove("timeoutMs");
      assertThatThrownBy(() -> mapper.treeToValue(tree, RecordCurrentTargetsQuery.class))
          .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
  }

  @Test
  void rejectsAmbiguousSelectionsAndUnboundedTimeouts() {
    assertThatThrownBy(() -> new RecordCurrentTargetsQuery("1", null, null, null, 30000))
        .isInstanceOf(IllegalArgumentException.class);
    var related =
        new RelatedRecordPageQuery(
            page, "relation", "target", "22222222-2222-2222-2222-222222222222");
    assertThatThrownBy(() -> new RecordCurrentTargetsQuery("1", null, page, related, 30000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordCurrentTargetsQuery("2", null, page, null, 30000))
        .isInstanceOf(IllegalArgumentException.class);
    for (int timeout : List.of(0, -1, 30001)) {
      assertThatThrownBy(() -> new RecordCurrentTargetsQuery("1", null, page, null, timeout))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
