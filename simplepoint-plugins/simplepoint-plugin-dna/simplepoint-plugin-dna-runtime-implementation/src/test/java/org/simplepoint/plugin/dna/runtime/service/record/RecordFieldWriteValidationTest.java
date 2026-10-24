package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFieldWriteRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordStateTransitions;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;

class RecordFieldWriteValidationTest {
  private final RecordStateTransitions states = new RecordStateTransitions("state", List.of("DRAFT"),
      Map.of("DRAFT", List.of("DRAFT", "SUBMITTED"), "SUBMITTED", List.of("SUBMITTED")));
  private final Map<String, Map<String, LogicalFieldDefinition>> fields = Map.of(
      "parent", Map.of("state", field("state"), "code", field("code"), "readonly", field("readonly")),
      "lines", Map.of("price", field("price")));

  @Test
  void priorParentStateControlsCreateOnlyReadonlyAndOwnedRowWritesAfterColumnRename() {
    var validation = new RecordFieldWriteValidation(fields, definition(List.of(
        rule("parent", "code", true, List.of()), rule("parent", "readonly", false, List.of()),
        rule("lines", "price", true, List.of("DRAFT")))));
    validation.validate("parent", Map.of("new_code", "initial"), null);
    validation.validate("lines", Map.of("new_price", "initial"), null);
    validation.validate("lines", Map.of("new_price", "changed"), Map.of("new_state", "DRAFT"));
    validation.validate("parent", Map.of(), Map.of("new_state", "SUBMITTED"));
    assertThatThrownBy(() -> validation.validate("parent", Map.of("new_code", "same"), Map.of("new_state", "DRAFT")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validate("parent", Map.of("new_readonly", "value"), null)).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validate("lines", Map.of("new_price", "new row"), Map.of("new_state", "SUBMITTED")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validate("lines", Map.of("new_price", "value"), Map.of("new_state", "UNKNOWN")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation.validate("lines", Map.of("new_price", "value"), Map.of())).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void refusesUnknownFieldsAndStatesAndCannotGuardTheLifecycleControlOrCalculatedOutputs() {
    for (var rule : List.of(rule("missing", "code", true, List.of()), rule("parent", "absent", true, List.of()),
        rule("parent", "state", true, List.of()), rule("parent", "code", true, List.of("OTHER")))) {
      assertThatThrownBy(() -> new RecordFieldWriteValidation(fields, definition(List.of(rule)))).isInstanceOf(DnaRuntimeException.class);
    }
    var rule = rule("parent", "code", false, List.of());
    assertThatThrownBy(() -> new RecordFieldWriteValidation(fields,
        new RecordCalculationDefinition("1", Map.of("code", "1"), Map.of(), null, null, states, List.of(rule))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> new RecordFieldWriteValidation(fields,
        new RecordCalculationDefinition("1", Map.of(), Map.of(), null, null, null, List.of(rule))))
        .isInstanceOf(DnaRuntimeException.class);
    new RecordFieldWriteValidation(fields, new RecordCalculationDefinition("1", Map.of(), Map.of()))
        .validate("parent", Map.of("new_code", "legacy"), null);
  }

  private RecordCalculationDefinition definition(List<RecordFieldWriteRule> rules) {
    return new RecordCalculationDefinition("1", Map.of(), Map.of(), null, null, states, rules);
  }

  private static RecordFieldWriteRule rule(String scope, String field, boolean create, List<String> states) {
    return new RecordFieldWriteRule(new RecordValidationField(scope, field), create, states);
  }

  private static LogicalFieldDefinition field(String id) {
    return new LogicalFieldDefinition(id, "new_" + id, id, LogicalFieldType.STRING, true, 40, null, null, null);
  }
}
