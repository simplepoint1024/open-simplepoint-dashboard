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

  @Test
  void newlyComputedOutputsAreReadOnlyEvenOnAnOlderStoredPage() {
    var page = calculationDocument(null);
    var current = calculationDocument("1+2");
    var effective = FormWritePolicyProjection.apply(page, current).fields().getFirst();
    assertThat(effective.storageMode()).isEqualTo(FormStorageMode.COMPUTED);
    assertThat(effective.calculation().expression()).isEqualTo("1+2");
    for (var state : Arrays.asList(null, FormRecordState.DRAFT, FormRecordState.SUBMITTED)) {
      assertThat(effective.allowsWrite(state)).isFalse();
    }
    assertThat(page.fields().getFirst().storageMode()).isEqualTo(FormStorageMode.STORED);
    assertThat(page.fields().getFirst().calculation()).isNull();
  }

  @Test
  void storedDefaultsAndCreateOnlyPolicyCannotContaminateTheNewCalculationContract() {
    var stored = new FormFieldDefinition("total", "amount", "Original", "Amount", FormFieldType.DECIMAL, FormStorageMode.STORED,
        false, true, null, 18, 2, new org.simplepoint.plugin.app.form.api.model.FormValue(
            org.simplepoint.plugin.app.form.api.model.FormValueKind.DECIMAL, "10"), List.of(), FormFieldWritePolicy.CREATE_ONLY);
    var page = new ApplicationFormDocument("1", List.of(stored), List.of(), List.of(), List.of());
    var projected = FormWritePolicyProjection.apply(page, calculationDocument("1+2"));
    var calculated = projected.fields().getFirst();
    assertThat(calculated.defaultValue()).isNull();
    assertThat(calculated.writePolicy()).isNull();
    assertThat(calculated.storageRequired()).isTrue();
    new FormCalculationCompiler(java.time.Clock.systemUTC()).compile(projected);
    assertThat(page.fields().getFirst().defaultValue().value()).isEqualTo("10");
    assertThat(page.fields().getFirst().writePolicy()).isEqualTo(FormFieldWritePolicy.CREATE_ONLY);
  }

  @Test
  void historicalCalculatedPagesCannotRestoreRemovedOrSupersededFormulas() {
    var old = calculationDocument("1+2");
    var replacement = FormWritePolicyProjection.apply(old, calculationDocument("4+5")).fields().getFirst();
    assertThat(replacement.calculation().expression()).isEqualTo("4+5");
    var removed = FormWritePolicyProjection.apply(old, calculationDocument(null)).fields().getFirst();
    assertThat(removed.calculation()).isNull();
    assertThat(removed.storageMode()).isEqualTo(FormStorageMode.STORED);
    assertThat(removed.allowsWrite(FormRecordState.DRAFT)).isTrue();
    assertThat(old.fields().getFirst().calculation().expression()).isEqualTo("1+2");
  }

  private static ApplicationFormDocument calculationDocument(String expression) {
    return new ApplicationFormDocument("1", List.of(FormCalculationPublicationProjectionTest.number("total", expression)),
        List.of(), List.of(), List.of());
  }

  private static ApplicationFormDocument document(final FormFieldWritePolicy policy, final String label) {
    return new ApplicationFormDocument("1", List.of(new FormFieldDefinition("name-field", "name", label, "Name",
        FormFieldType.STRING, FormStorageMode.STORED, false, false, 120, null, null, null, List.of(), policy)),
        List.of(), List.of(), List.of());
  }
}
