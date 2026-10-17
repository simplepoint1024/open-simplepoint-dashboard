package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

class FormWritePolicyProjectionTest {

  @Test
  void everyPolicyCombinationIsTheIntersectionOfPageAndActivatedRestrictions() {
    for (var pagePolicy : FormFieldWritePolicy.values()) {
      for (var governingPolicy : FormFieldWritePolicy.values()) {
        var original = document(pagePolicy, "Original label");
        var projected = FormWritePolicyProjection.apply(original, document(governingPolicy, "New label"));
        var field = projected.fields().getFirst();
        for (var state : Arrays.asList(null, FormRecordState.DRAFT, FormRecordState.SUBMITTED)) {
          assertThat(field.effectiveWritePolicy().allows(state))
              .as("%s / %s / %s", pagePolicy, governingPolicy, state)
              .isEqualTo(pagePolicy.allows(state) && governingPolicy.allows(state));
        }
        assertThat(field.label()).isEqualTo("Original label");
        assertThat(field.storageCode()).isEqualTo("name");
        assertThat(original.fields().getFirst().effectiveWritePolicy()).isEqualTo(pagePolicy);
      }
    }
  }

  @Test
  void governingFieldsDoNotChangeTheHistoricalStorageProjectionOrAddNewInputs() {
    var original = document(FormFieldWritePolicy.EDITABLE, "Old");
    var governing = new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of());
    assertThat(FormWritePolicyProjection.apply(original, governing)).isEqualTo(original);
    assertThat(FormWritePolicyProjection.apply(governing, original).fields()).isEmpty();
  }

  private static ApplicationFormDocument document(final FormFieldWritePolicy policy, final String label) {
    return new ApplicationFormDocument("1", List.of(new FormFieldDefinition("name-field", "name", label, "Name",
        FormFieldType.STRING, FormStorageMode.STORED, false, false, 120, null, null, null, List.of(), policy)),
        List.of(), List.of(), List.of());
  }
}
