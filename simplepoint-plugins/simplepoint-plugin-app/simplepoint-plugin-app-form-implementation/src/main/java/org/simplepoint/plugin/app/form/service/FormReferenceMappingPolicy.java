package org.simplepoint.plugin.app.form.service;

import java.util.HashSet;
import java.util.List;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;

/**
 * Structural value providers; runtime authorization and confirmed source versions remain separate.
 */
final class FormReferenceMappingPolicy {
  private FormReferenceMappingPolicy() {}

  static void validateSources(ApplicationFormDocument document) {
    var providers = new HashSet<FormRelationField>();
    var references = new HashSet<FormRelationField>();
    document
        .relations()
        .forEach(
            relation -> {
              if (relation.sourceField() != null) {
                references.add(relation.sourceField());
              }
            });
    int count = 0;
    for (var relation : document.relations()) {
      if (relation.retired()) {
        continue;
      }
      count += relation.mappings().size();
      if (count > 128) {
        throw invalid("A form supports at most 128 reference mappings");
      }
      if (relation.mappings().isEmpty()) {
        continue;
      }
      var owner = relation.sourceField();
      if (owner == null) {
        throw invalid("A scalar mapping requires a single-valued reference");
      }
      var fields = fields(document, owner.detailId());
      for (var mapping : relation.mappings()) {
        var key = new FormRelationField(owner.detailId(), mapping.destinationFieldId());
        var field = field(fields, mapping.destinationFieldId());
        if (!providers.add(key)
            || references.contains(key)
            || field.calculation() != null
            || field.type() == FormFieldType.JSON
            || field.defaultValue() != null && field.defaultValue().kind() != FormValueKind.NULL) {
          throw invalid("A mapped field requires one unambiguous value provider");
        }
        if (mapping.mode() == FormReferenceMapping.Mode.LIVE) {
          if (field.storageMode() != FormStorageMode.TRANSIENT
              || field.storageRequired()
              || field.submitRequired()) {
            throw invalid("Live reference values are optional, non-persistent projections");
          }
        } else if (field.storageMode() != FormStorageMode.STORED) {
          throw invalid("Copied reference values require an ordinary stored destination");
        }
      }
    }
  }

  static void validateTarget(
      ApplicationFormDocument source,
      FormRelationDefinition relation,
      ApplicationFormDocument target) {
    if (relation.mappings().isEmpty()) {
      return;
    }
    var destinations = fields(source, relation.sourceField().detailId());
    for (var mapping : relation.mappings()) {
      var origin = field(target.fields(), mapping.targetFieldId());
      var destination = field(destinations, mapping.destinationFieldId());
      if (!origin.persistent()
          || origin.storageMode() == FormStorageMode.ATTACHMENT
          || !compatible(mapping.conversion(), origin.type(), destination.type())) {
        throw invalid("Reference mapping field types are incompatible");
      }
    }
  }

  static boolean compatible(
      FormReferenceMapping.Conversion conversion, FormFieldType origin, FormFieldType destination) {
    return origin != null
        && destination != null
        && org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion.valueOf(
                conversion.name())
            .supports(
                org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.valueOf(
                    origin.name()),
                org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.valueOf(
                    destination.name()));
  }

  private static List<FormFieldDefinition> fields(
      ApplicationFormDocument document, String detailId) {
    return detailId == null
        ? document.fields()
        : document.details().stream()
            .filter(detail -> detail.detailId().equals(detailId))
            .findFirst()
            .orElseThrow(() -> invalid("Reference mapping owner detail is unknown"))
            .fields();
  }

  private static FormFieldDefinition field(List<FormFieldDefinition> fields, String id) {
    return fields.stream()
        .filter(field -> field.fieldId().equals(id))
        .findFirst()
        .orElseThrow(() -> invalid("Reference mapping field is unknown"));
  }

  private static ApplicationFormException invalid(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }
}
