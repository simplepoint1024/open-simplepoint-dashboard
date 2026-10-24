package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldAccess;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

class FormCalculationWriteProjectionTest {
  @Test
  void neutralInstalledRulesMatchEveryOperationAndLifecycleCombinationForMainAndOwnedFields() {
    var fields = new ArrayList<FormFieldDefinition>();
    for (boolean readonly : List.of(false, true)) {
      for (var create : FormFieldAccess.CreateMode.values()) {
        for (var edit : FormFieldAccess.EditMode.values()) {
          for (var lifecycle : FormFieldWritePolicy.values()) {
            String id = "field" + fields.size();
            fields.add(new FormFieldDefinition(id, id, id, id, FormFieldType.STRING, FormStorageMode.STORED,
                false, false, 100, null, null, null, List.of(), lifecycle, null, new FormFieldAccess(readonly, create, edit)));
          }
        }
      }
    }
    var document = new ApplicationFormDocument("1", fields, List.of(), List.of(), List.of(),
        List.of(new FormDetailDefinition("lines", "lines", "Lines", fields, List.of())));
    var projected = FormCalculationPublicationProjectionTest.projection().compile(
        FormCalculationPublicationProjectionTest.version(document), "model-a", true);
    for (String scope : List.of("parent", "lines")) {
      for (var field : fields) {
        var rule = projected.writeRules().stream().filter(item -> item.field().scope().equals(scope)
            && item.field().fieldId().equals(field.fieldId())).findFirst().orElse(null);
        assertThat(rule == null || rule.create()).isEqualTo(field.allowsWrite(null));
        for (var state : FormRecordState.values()) {
          assertThat(rule == null || rule.updateStates().contains(state.name())).isEqualTo(field.allowsWrite(state));
        }
      }
    }
  }

  @Test
  void computedOutputsAreProtectedByCalculationAdmissionInsteadOfOrdinaryWriteRules() {
    var computed = new FormFieldDefinition("computed", "computed", "Computed", "Computed", FormFieldType.STRING, FormStorageMode.COMPUTED,
        false, false, 100, null, null, null, List.of(), null, null, null, new FormCalculation("1", "\"value\""));
    var legacy = new FormFieldDefinition("legacy", null, "Legacy", null, FormFieldType.STRING, FormStorageMode.COMPUTED,
        false, false, null, null, null, null, List.of());
    var document = new ApplicationFormDocument("1", List.of(computed, legacy), List.of(), List.of(), List.of());
    assertThat(FormCalculationWriteProjection.compile(document)).isEmpty();
  }
}
