package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordStateTransitionsTest {
  @Test
  void oldDefinitionsRemainUnchangedAndStateSetsHaveCanonicalOrder() throws Exception {
    var json = new ObjectMapper();
    var old = new RecordCalculationDefinition("1", Map.of(), Map.of());
    assertThat(json.writeValueAsString(old)).doesNotContain("stateTransitions");
    var lifecycle = new RecordStateTransitions("state", List.of("SUBMITTED", "DRAFT"),
        Map.of("DRAFT", List.of("SUBMITTED", "DRAFT"), "SUBMITTED", List.of("SUBMITTED")));
    var otherOrder = new RecordStateTransitions("state", List.of("DRAFT", "SUBMITTED"),
        Map.of("SUBMITTED", List.of("SUBMITTED"), "DRAFT", List.of("DRAFT", "SUBMITTED")));
    assertThat(json.writeValueAsString(lifecycle)).isEqualTo(json.writeValueAsString(otherOrder));
    var definition = new RecordCalculationDefinition("1", Map.of(), Map.of(), null, null, lifecycle);
    assertThat(definition.isEmpty()).isFalse();
    assertThat(json.readValue(json.writeValueAsBytes(definition), RecordCalculationDefinition.class)).isEqualTo(definition);
    assertThatThrownBy(() -> lifecycle.transitions().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> lifecycle.initialStates().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsUnknownInitialStatesTargetsDuplicatesAndInvalidIdentities() {
    assertThatThrownBy(() -> new RecordStateTransitions("state", List.of("NEW"), Map.of("DRAFT", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordStateTransitions("state", List.of("DRAFT"), Map.of("DRAFT", List.of("NEW"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordStateTransitions("state", List.of("DRAFT", "DRAFT"), Map.of("DRAFT", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordStateTransitions("state", List.of("not a state"), Map.of("not a state", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
