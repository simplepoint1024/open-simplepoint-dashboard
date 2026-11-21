package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordReferenceSnapshotTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void legacyAggregateRetainsExactJsonAndOptionalRuleMetadataIsAbsent() throws Exception {
    String id = UUID.randomUUID().toString();
    String legacy =
        "{\"parent\":{\"id\":\"" + id + "\",\"version\":1,\"values\":{}},\"details\":{}}";
    var view = json.readValue(legacy, OwnedRecordView.class);
    assertThat(view.referenceSnapshots()).isEmpty();
    assertThat(json.writeValueAsString(view)).isEqualTo(legacy);
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("parent", "customer"),
            "customer-model",
            "app",
            "customer-form",
            "customers",
            null,
            null);
    assertThat(json.writeValueAsString(rule)).doesNotContain("snapshotLabelFieldId");
  }

  @Test
  void frozenLabelsRoundTripWithProvenanceAndCannotBeMutated() throws Exception {
    var label = label();
    var labels = new ArrayList<>(List.of(label));
    var view =
        new OwnedRecordView(new RecordView(label.sourceRecordId(), 1, Map.of()), Map.of(), labels);
    labels.clear();
    assertThat(json.readValue(json.writeValueAsString(view), OwnedRecordView.class))
        .isEqualTo(view);
    assertThat(view.referenceSnapshots()).containsExactly(label);
    assertThatThrownBy(() -> view.referenceSnapshots().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new OwnedRecordView(view.parent(), Map.of(), List.of(label, label)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RecordReferenceSnapshot label() {
    return new RecordReferenceSnapshot(
        "customer",
        "trade-model",
        UUID.randomUUID().toString(),
        "customer",
        "customer-model",
        "app",
        "customer-form",
        "customers",
        UUID.randomUUID().toString(),
        7,
        "name",
        "客户-日本語");
  }
}
