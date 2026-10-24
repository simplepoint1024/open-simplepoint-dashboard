package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordStateTransitions;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

class RecordStateTransitionValidationTest {
  private final RecordStateTransitions definition = new RecordStateTransitions("state", List.of("DRAFT"),
      Map.of("DRAFT", List.of("DRAFT", "SUBMITTED"), "SUBMITTED", List.of("SUBMITTED")));

  @Test
  void validatesInitialAndPriorStatesAgainstStableIdentityMappedToTheCurrentColumn() {
    var validation = new RecordStateTransitionValidation(Map.of("state", field(LogicalFieldType.STRING)), Set.of(), definition);
    validation.validateTransition(null, Map.of("renamed_state", "DRAFT"));
    validation.validateTransition(Map.of("renamed_state", "DRAFT"), Map.of("renamed_state", "SUBMITTED"));
    validation.validateTransition(Map.of("renamed_state", "SUBMITTED"), Map.of("renamed_state", "SUBMITTED"));
    assertThatThrownBy(() -> validation.validateTransition(null, Map.of("renamed_state", "SUBMITTED")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validateTransition(Map.of("renamed_state", "SUBMITTED"), Map.of("renamed_state", "DRAFT")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validateTransition(Map.of("renamed_state", "UNKNOWN"), Map.of("renamed_state", "DRAFT")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validateTarget(Map.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validateTarget(Map.of("renamed_state", "UNKNOWN"))).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void rejectsMissingNumericAndCalculatedStateFieldsBeforeAnyEvaluation() {
    assertThatThrownBy(() -> new RecordStateTransitionValidation(Map.of(), Set.of(), definition)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> new RecordStateTransitionValidation(Map.of("state", field(LogicalFieldType.INT32)), Set.of(), definition))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> new RecordStateTransitionValidation(Map.of("state", field(LogicalFieldType.STRING)), Set.of("state"), definition))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void absentMetadataRetainsLegacyBehavior() {
    var legacy = new RecordStateTransitionValidation(Map.of(), Set.of(), null);
    legacy.validateTarget(Map.of());
    legacy.validateTransition(null, Map.of());
    legacy.validateTransition(Map.of("state", "anything"), Map.of());
  }

  private static LogicalFieldDefinition field(LogicalFieldType type) {
    return new LogicalFieldDefinition("state", "renamed_state", "State", type, false,
        type == LogicalFieldType.STRING ? 16 : null, null, null, null);
  }
}
