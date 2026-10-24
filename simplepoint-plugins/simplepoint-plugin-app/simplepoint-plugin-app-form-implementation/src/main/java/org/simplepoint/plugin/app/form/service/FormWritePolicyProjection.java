package org.simplepoint.plugin.app.form.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;

/** Applies the activated form-wide write floor without rewriting an immutable page definition. */
final class FormWritePolicyProjection {

  private FormWritePolicyProjection() {
  }

  static ApplicationFormDocument apply(
      final ApplicationFormDocument page,
      final ApplicationFormDocument governing
  ) {
    Map<String, FormFieldDefinition> policies = governing.fields().stream()
        .filter(FormWritePolicyProjection::persistent)
        .collect(Collectors.toUnmodifiableMap(FormFieldDefinition::fieldId, Function.identity()));
    return new ApplicationFormDocument(page.contractVersion(), page.fields().stream().map(field -> {
      FormFieldDefinition policy = policies.get(field.fieldId());
      if (policy == null || !persistent(field)) {
        return field;
      }
      FormFieldWritePolicy effective = stricter(field.effectiveWritePolicy(), policy.effectiveWritePolicy());
      var access = org.simplepoint.plugin.app.form.api.model.FormFieldAccess.intersect(field.access(), policy.access());
      // Current governance owns calculation mode; an old page cannot accept a new output or revive a removed formula.
      var mode = policy.storageMode() == FormStorageMode.COMPUTED || field.storageMode() == FormStorageMode.COMPUTED
          ? policy.storageMode() : field.storageMode();
      var calculation = mode == FormStorageMode.COMPUTED ? policy.calculation() : null;
      if (effective == field.effectiveWritePolicy() && java.util.Objects.equals(access, field.access())
          && mode == field.storageMode() && java.util.Objects.equals(calculation, field.calculation())) {
        return field;
      }
      return new FormFieldDefinition(field.fieldId(), field.storageCode(), field.label(), field.storageLabel(),
          field.type(), mode, field.submitRequired(), field.storageRequired(), field.length(),
          field.precision(), field.scale(), calculation == null ? field.defaultValue() : null, field.choices(),
          calculation == null ? effective : null, field.validation(), access,
          calculation, field.labelLocalization());
    }).toList(), page.indexes(), page.scenes(), page.rules(), governing.details(), governing.constraints(), page.presentation());
  }

  private static FormFieldWritePolicy stricter(final FormFieldWritePolicy left, final FormFieldWritePolicy right) {
    if (left == FormFieldWritePolicy.CREATE_ONLY || right == FormFieldWritePolicy.CREATE_ONLY) {
      return FormFieldWritePolicy.CREATE_ONLY;
    }
    if (left == FormFieldWritePolicy.UNTIL_SUBMITTED || right == FormFieldWritePolicy.UNTIL_SUBMITTED) {
      return FormFieldWritePolicy.UNTIL_SUBMITTED;
    }
    return FormFieldWritePolicy.EDITABLE;
  }

  private static boolean persistent(final FormFieldDefinition field) {
    return field.persistent();
  }
}
