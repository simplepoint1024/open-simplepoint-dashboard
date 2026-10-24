package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OwnedRecordPreviewTest {
  @Test
  void preservesNullableExactValuesAndDefensivelyCopiesEveryCollection() throws Exception {
    var parent = new LinkedHashMap<String, Object>();
    parent.put("amount", "9007199254740993.01");
    parent.put("optional", null);
    var row = new LinkedHashMap<String, Object>(parent);
    var rows = new ArrayList<Map<String, Object>>(List.of(row));
    var details = new LinkedHashMap<String, List<Map<String, Object>>>();
    details.put("detail", rows);
    var preview = new OwnedRecordPreview(parent, details, 7);
    parent.clear();
    row.clear();
    rows.clear();
    details.clear();
    assertThat(preview.values()).containsEntry("amount", "9007199254740993.01").containsEntry("optional", null);
    assertThat(preview.details().get("detail")).containsExactly(preview.values());
    var json = new ObjectMapper();
    assertThat(json.readValue(json.writeValueAsBytes(preview), OwnedRecordPreview.class)).isEqualTo(preview);
    assertThatThrownBy(() -> preview.values().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> preview.details().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> preview.details().get("detail").clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> preview.details().get("detail").getFirst().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new OwnedRecordPreview(Map.of(), Map.of(), -1)).isInstanceOf(IllegalArgumentException.class);
  }
}
