package org.simplepoint.plugin.app.form.service;

import java.util.Map;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

/** One authoritative root/owned source-field projection for designer, publication and runtime. */
record FormRelationSourceField(
    FormFieldDefinition field, String modelId, FormDetailDefinition detail) {
  static FormRelationSourceField resolve(
      PublishedApplicationForm source, FormRelationField reference) {
    if (reference == null) {
      throw unavailable();
    }
    var detail =
        reference.detailId() == null
            ? null
            : source.document().details().stream()
                .filter(value -> value.detailId().equals(reference.detailId()))
                .findFirst()
                .orElseThrow(FormRelationSourceField::unavailable);
    var fields = detail == null ? source.document().fields() : detail.fields();
    String model =
        detail == null
            ? source.physicalModelId()
            : source.detailBindings().stream()
                .filter(value -> value.detailId().equals(detail.detailId()))
                .findFirst()
                .orElseThrow(FormRelationSourceField::unavailable)
                .physicalModelId();
    var field =
        fields.stream()
            .filter(value -> value.fieldId().equals(reference.fieldId()))
            .findFirst()
            .orElseThrow(FormRelationSourceField::unavailable);
    return new FormRelationSourceField(field, model, detail);
  }

  boolean readable(Map<String, String> policy) {
    String access =
        RecordPermissionModel.access(policy, modelId, field.fieldId(), field.storageCode());
    return !"HIDDEN".equals(access) && !"MASKED".equals(access);
  }

  private static ApplicationFormException unavailable() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
        "The selected business relation field is unavailable");
  }
}
