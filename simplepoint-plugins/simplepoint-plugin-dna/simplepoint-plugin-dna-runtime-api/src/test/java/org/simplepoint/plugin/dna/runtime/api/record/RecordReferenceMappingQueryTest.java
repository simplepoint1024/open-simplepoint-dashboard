package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;

class RecordReferenceMappingQueryTest {
  @Test
  void explicitClearRoundTripsButOmittedSelectionCannotBecomeAnUnlink() throws Exception {
    var mapper = new ObjectMapper();
    var query = query(List.of("second", "first"));
    assertThat(query.applyDefaultMappingIds()).containsExactly("first", "second");
    assertThat(mapper.readValue(mapper.writeValueAsBytes(query), RecordReferenceMappingQuery.class))
        .isEqualTo(query);
    var tree = mapper.valueToTree(query);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("targetRecordId");
    assertThatThrownBy(() -> mapper.treeToValue(tree, RecordReferenceMappingQuery.class))
        .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    assertThatThrownBy(() -> query(List.of("same", "same")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalPreviewRetainsPrecisionAndSeparatesManagedFromEditableOutputs() throws Exception {
    var mapper = new ObjectMapper();
    var observation =
        new RecordReferenceMappingObservation(
            "a".repeat(64), "11111111-1111-1111-1111-111111111111", 0L);
    var precise = new LogicalValue(LogicalValueKind.DECIMAL, "12345678901234567890.12345678");
    var result =
        new RecordReferenceMappingView(
            observation,
            Map.of("price", precise),
            Map.of("name", new LogicalValue(LogicalValueKind.STRING, "日本語😀")));
    assertThat(mapper.readValue(mapper.writeValueAsBytes(result), RecordReferenceMappingView.class))
        .isEqualTo(result);
    assertThatThrownBy(
            () ->
                new RecordReferenceMappingView(
                    observation, Map.of("price", precise), Map.of("price", precise)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordReferenceMappingQuery query(List<String> defaults) {
    return new RecordReferenceMappingQuery(
        "1",
        "model",
        new RecordSchemaReference(1, "a".repeat(64)),
        1,
        "customer",
        null,
        defaults,
        30000);
  }
}
