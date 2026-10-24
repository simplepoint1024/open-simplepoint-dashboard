package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotFieldRequirement;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

class RecordSnapshotValidationTest {
  @Test
  void validatesFinalProjectionByStableIdentityAndPreservesFalseZeroAndOptionalNull() {
    var required = validation(LogicalFieldType.STRING, requirement(true, null, null, false), Set.of("renamed"), Set.of());
    required.validate(Map.of("renamed", "有效"));
    assertThatThrownBy(() -> required.validate(Map.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> required.validate(Collections.singletonMap("renamed", null))).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> required.validate(Map.of("renamed", "  "))).isInstanceOf(DnaRuntimeException.class);
    validation(LogicalFieldType.INT32, requirement(true, null, null, false), Set.of("renamed"), Set.of()).validate(Map.of("renamed", 0));
    validation(LogicalFieldType.BOOLEAN, requirement(true, null, null, false), Set.of("renamed"), Set.of()).validate(Map.of("renamed", false));
    validation(LogicalFieldType.STRING, requirement(false, 100, "[a-z]+", false), Set.of("renamed"), Set.of())
        .validate(Collections.singletonMap("renamed", null));
  }

  @Test
  void boundsWireTextAndRoleFormatsWithoutExposingSubmittedValues() {
    var wire = validation(LogicalFieldType.STRING, requirement(true, 4, null, false), Set.of("renamed"), Set.of());
    wire.validate(Map.of("renamed", "😀😀"));
    assertThatThrownBy(() -> wire.validate(Map.of("renamed", "😀😀a"))).isInstanceOf(DnaRuntimeException.class)
        .hasMessageNotContaining("😀");
    var role = validation(LogicalFieldType.STRING, requirement(false, 100, "[^,]*", true), Set.of("renamed"), Set.of());
    role.validate(Map.of("renamed", "finance"));
    for (var invalid : List.of("", " ", " finance", "finance ", "finance,admin", "finance\nadmin")) {
      assertThatThrownBy(() -> role.validate(Map.of("renamed", invalid))).isInstanceOf(DnaRuntimeException.class);
    }
    var user = validation(LogicalFieldType.STRING, requirement(false, 100, "[A-Za-z0-9_][A-Za-z0-9_.:@-]{0,99}", false), Set.of("renamed"), Set.of());
    user.validate(Map.of("renamed", "department:user-1"));
    assertThatThrownBy(() -> user.validate(Map.of("renamed", "用户"))).isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void rejectsUnavailableFieldsWrongTypesAndUnboundedPatternFeaturesBeforeWriting() {
    var rule = requirement(false, 100, null, false);
    assertThatThrownBy(() -> validation(LogicalFieldType.STRING, rule, Set.of(), Set.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation(LogicalFieldType.STRING, rule, Set.of("renamed"), Set.of("renamed")))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation(LogicalFieldType.INT32, rule, Set.of("renamed"), Set.of())).isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> validation(LogicalFieldType.STRING, requirement(false, null, "(?=secret)", false), Set.of("renamed"), Set.of()))
        .isInstanceOf(DnaRuntimeException.class);
    var unknown = new RecordSnapshotFieldRequirement("unknown", false, null, null, false, false, false);
    assertThatThrownBy(() -> validation(LogicalFieldType.STRING, unknown, Set.of("renamed"), Set.of())).isInstanceOf(DnaRuntimeException.class);
  }

  private static RecordSnapshotFieldRequirement requirement(boolean required, Integer maximum, String pattern, boolean role) {
    return new RecordSnapshotFieldRequirement("stable", required, maximum, pattern, role, role, role);
  }

  private static RecordSnapshotValidation validation(LogicalFieldType type, RecordSnapshotFieldRequirement requirement,
      Set<String> readable, Set<String> masked) {
    var schema = new PhysicalSchemaDefinition(org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion.CURRENT, List.of(new LogicalFieldDefinition("stable", "renamed", "Field", type, true,
        type == LogicalFieldType.STRING ? 5000 : null, null, null, null)), List.of());
    var model = new RuntimeRecordModel("model", "app", "form", 1, schema, null);
    var access = new RecordAccessContext(null, "page", readable, Set.of(), masked, null, null);
    return new RecordSnapshotValidation(model, access, List.of(requirement));
  }
}
