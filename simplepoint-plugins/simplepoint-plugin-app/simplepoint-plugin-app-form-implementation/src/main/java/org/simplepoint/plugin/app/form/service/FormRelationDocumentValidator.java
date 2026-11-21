package org.simplepoint.plugin.app.form.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.component.ReferenceComponentContributor;

/**
 * Local structural checks only; publication separately resolves target identity and authorization.
 */
final class FormRelationDocumentValidator {
  private static final int MAX_RELATIONS = 50;

  private FormRelationDocumentValidator() {}

  static void validate(ApplicationFormDocument document) {
    FormReferenceMappingPolicy.validateSources(document);
    document.scenes().forEach(scene -> validateComponent(scene.root(), document));
    if (document.relations().size() > MAX_RELATIONS) {
      invalid("A form supports at most fifty business relations");
    }
    Set<String> identities = new HashSet<>();
    Set<FormRelationField> sources = new HashSet<>();
    for (var relation : document.relations()) {
      if (relation.collectionReplacement() != null
          && document.relations().stream().anyMatch(
              item -> item.relationId().equals(relation.collectionReplacement().sourceRelationId()))) {
        invalid("A collection replacement must remove its source relation");
      }
      if (!relation.retired()) {
        FormReferenceContextPolicy.validateSources(document, relation);
      }
      if (!identities.add(relation.relationId())) {
        invalid("Form relation identity is duplicated");
      }
      var source = relation.sourceField();
      if (source == null) {
        continue; // M:N is a root collection, never a field containing an ID array.
      }
      if (!sources.add(source)) {
        invalid("A business reference field can own only one relation");
      }
      List<FormFieldDefinition> fields =
          source.detailId() == null
              ? document.fields()
              : document.details().stream()
                  .filter(detail -> detail.detailId().equals(source.detailId()))
                  .findFirst()
                  .orElseThrow(() -> invalidFailure("Relation source detail is unknown"))
                  .fields();
      var field =
          fields.stream()
              .filter(candidate -> candidate.fieldId().equals(source.fieldId()))
              .findFirst()
              .orElseThrow(() -> invalidFailure("Relation source field is unknown"));
      validateField(field);
    }
  }

  private static void validateComponent(
      FormComponentDefinition component, ApplicationFormDocument document) {
    if (ReferenceComponentContributor.TYPE.equals(component.componentType())) {
      var field =
          document.fields().stream()
              .filter(candidate -> candidate.fieldId().equals(component.fieldId()))
              .findFirst()
              .orElseThrow(() -> invalidFailure("Business reference component field is unknown"));
      validateField(field);
    }
    component.children().forEach(child -> validateComponent(child, document));
  }

  private static void validateField(FormFieldDefinition field) {
    if (field.type() != FormFieldType.UUID
        || field.storageMode() != FormStorageMode.REFERENCE
        || field.calculation() != null
        || !field.choices().isEmpty()
        || field.defaultValue() != null && field.defaultValue().kind() != FormValueKind.NULL) {
      invalid("Business reference requires a UUID reference field without a static target default");
    }
  }

  static boolean isReference(ApplicationFormDocument document, String detailId, String fieldId) {
    return document.relations().stream()
        .map(relation -> relation.sourceField())
        .anyMatch(
            source ->
                source != null
                    && java.util.Objects.equals(detailId, source.detailId())
                    && java.util.Objects.equals(fieldId, source.fieldId()));
  }

  static boolean isBusinessReference(
      ApplicationFormDocument document, String detailId, String fieldId) {
    return isReference(document, detailId, fieldId)
        || detailId == null && document.scenes().stream()
            .anyMatch(scene -> usesReferenceComponent(scene.root(), fieldId));
  }

  private static boolean usesReferenceComponent(FormComponentDefinition component, String fieldId) {
    return component != null
        && ((ReferenceComponentContributor.TYPE.equals(component.componentType())
                && fieldId.equals(component.fieldId()))
            || component.children().stream().anyMatch(child -> usesReferenceComponent(child, fieldId)));
  }

  private static void invalid(String message) {
    throw invalidFailure(message);
  }

  private static ApplicationFormException invalidFailure(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID, message);
  }
}
