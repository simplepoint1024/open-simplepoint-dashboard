package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRuntimeReference;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

/**
 * Source-local runtime capabilities; never projects target fields, physical identities or grants.
 */
final class FormReferenceMappingProjection {
  private FormReferenceMappingProjection() {}

  static FormRuntimeReference enrich(
      PublishedApplicationForm form,
      FormRelationDefinition relation,
      Map<String, String> permissions,
      FormRuntimeReference reference) {
    if (relation.mappings().isEmpty()) {
      return reference;
    }
    String detailId = relation.sourceField().detailId();
    var fields =
        detailId == null
            ? form.document().fields()
            : form.document().details().stream()
                .filter(detail -> detail.detailId().equals(detailId))
                .findFirst()
                .orElseThrow()
                .fields();
    String model =
        detailId == null
            ? form.physicalModelId()
            : form.detailBindings().stream()
                .filter(binding -> binding.detailId().equals(detailId))
                .findFirst()
                .orElseThrow()
                .physicalModelId();
    var mappings = new ArrayList<FormRuntimeReference.Mapping>();
    boolean writable = true;
    for (var mapping : relation.mappings()) {
      var field =
          fields.stream()
              .filter(candidate -> candidate.fieldId().equals(mapping.destinationFieldId()))
              .findFirst()
              .orElseThrow();
      String access =
          RecordPermissionModel.access(permissions, model, field.fieldId(), field.storageCode());
      boolean editable = access == null || "EDITABLE".equals(access);
      boolean readable = editable || "VISIBLE".equals(access);
      if (mapping.mode() == FormReferenceMapping.Mode.SERVER_SNAPSHOT && !editable) {
        writable = false;
      }
      if (readable && (mapping.mode() != FormReferenceMapping.Mode.EDITABLE_DEFAULT || editable)) {
        mappings.add(
            new FormRuntimeReference.Mapping(
                mapping.mappingId(), field.fieldId(), mapping.mode(), mapping.nullPolicy()));
      }
    }
    return new FormRuntimeReference(
        reference.relationId(),
        reference.allowDrillThrough(),
        reference.contextual(),
        reference.searchable(),
        reference.contextInputs(),
        List.copyOf(mappings),
        writable ? null : false);
  }

  static FormReferenceMapping.Mode mode(
      PublishedApplicationForm form, String detailId, String fieldId) {
    return form.document().relations().stream()
        .filter(
            relation ->
                !relation.retired() && relation.sourceField() != null
                    && Objects.equals(detailId, relation.sourceField().detailId()))
        .flatMap(relation -> relation.mappings().stream())
        .filter(mapping -> mapping.destinationFieldId().equals(fieldId))
        .map(FormReferenceMapping::mode)
        .findFirst()
        .orElse(null);
  }
}
