package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordJsonSelectionTest {
  @Test
  void oldFieldRulesRetainTheirShapeAndSelectionDomainsRoundTripImmutably() throws Exception {
    var json = new ObjectMapper();
    assertThat(json.writeValueAsString(new RecordFieldConstraints(null, null, null, List.of()))).isEqualTo("{\"choices\":[]}");
    var leaf = new ArrayList<>(List.of("中国", "上海"));
    var paths = new ArrayList<List<String>>(List.of(leaf));
    var domain = new RecordJsonSelection(RecordJsonSelection.Mode.PATH, paths);
    leaf.clear(); paths.clear();
    var field = new RecordFieldConstraints(null, null, null, List.of(), domain);
    assertThat(domain.paths()).containsExactly(List.of("中国", "上海"));
    assertThat(json.readValue(json.writeValueAsBytes(field), RecordFieldConstraints.class)).isEqualTo(field);
    assertThatThrownBy(() -> domain.paths().getFirst().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsAmbiguousDomainsAndCountsSelectionTextInTheWholeDefinitionBudget() {
    for (var paths : List.of(List.of(List.of("a"), List.of("a")), List.of(List.of("a"), List.of("a", "b")), List.of(List.of(" ")))) {
      assertThatThrownBy(() -> new RecordJsonSelection(RecordJsonSelection.Mode.PATH, paths)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new RecordJsonSelection(RecordJsonSelection.Mode.SET, List.of(List.of("a", "b"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordJsonSelection(RecordJsonSelection.Mode.PATH, List.of(List.of("1", "2", "3", "4", "5", "6", "7"))))
        .isInstanceOf(IllegalArgumentException.class);
    var domain = new RecordJsonSelection(RecordJsonSelection.Mode.SET,
        java.util.stream.IntStream.range(0, 200).mapToObj(i -> List.of("x".repeat(250) + i)).toList());
    var field = new RecordFieldConstraints(null, null, null, List.of(), domain);
    assertThatThrownBy(() -> new RecordCalculationDefinition("1", Map.of(), Map.of(), Map.of("a", field, "b", field)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordFieldConstraints(1, null, null, List.of(), domain)).isInstanceOf(IllegalArgumentException.class);
  }
}
