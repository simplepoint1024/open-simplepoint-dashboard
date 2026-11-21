package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.component.ReferenceComponentContributor;

/**
 * Publication completeness; target authorization and binding are resolved during version freeze.
 */
final class FormRelationPublicationValidator {
  private FormRelationPublicationValidator() {}

  static void validate(ApplicationFormDocument document) {
    document.relations().stream()
        .filter(relation -> relation.cardinality() == FormRelationCardinality.MANY_TO_MANY)
        .forEach(relation -> {
          var qualification = relation.qualification();
          var view = relation.referenceView();
          if (qualification != null
                  && qualification.recheck() != org.simplepoint.plugin.app.form.api.model.relation.FormRelationQualification.Recheck.ON_CHANGE
              || view != null && !view.contextFilters().isEmpty()) {
            throw new ApplicationFormException(
                ApplicationFormErrorCode.DOCUMENT_INVALID,
                "Collection qualification is evaluated when linking a saved record");
          }
        });
    document.scenes().forEach(scene -> validateComponent(scene.root(), document));
  }

  private static void validateComponent(
      FormComponentDefinition component, ApplicationFormDocument document) {
    if (ReferenceComponentContributor.TYPE.equals(component.componentType())
        && !FormRelationDocumentValidator.isReference(document, null, component.fieldId())) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.DOCUMENT_INVALID,
          "A business reference component requires a configured relation");
    }
    component.children().forEach(child -> validateComponent(child, document));
  }
}
