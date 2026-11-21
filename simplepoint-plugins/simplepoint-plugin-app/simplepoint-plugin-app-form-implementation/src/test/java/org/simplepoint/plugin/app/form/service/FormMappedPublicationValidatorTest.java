package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.validation.InvalidFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;

/** Value producer availability must not remove ordinary input requirements. */
class FormMappedPublicationValidatorTest {
  private final FormDocumentValidator validator =
      new FormDocumentValidator(new DefaultFormComponentRegistry());

  @Test
  void requiredSnapshotOutputCanPublishWithoutAnEditableInput() {
    validator.validatePublication(document(FormReferenceMapping.Mode.SERVER_SNAPSHOT, null, false));
  }

  @Test
  void optionalDefaultSelectionDoesNotReplaceAnEditableRequiredInput() {
    assertMissingInput(document(FormReferenceMapping.Mode.EDITABLE_DEFAULT, null, false));
    validator.validatePublication(document(FormReferenceMapping.Mode.EDITABLE_DEFAULT, null, true));
  }

  @Test
  void detailProviderDoesNotExemptAnUnrelatedRootFieldWithTheSameIdentity() {
    assertMissingInput(document(FormReferenceMapping.Mode.SERVER_SNAPSHOT, "lines", false));
  }

  private void assertMissingInput(ApplicationFormDocument document) {
    assertThatThrownBy(() -> validator.validatePublication(document))
        .isInstanceOfSatisfying(
            InvalidFormException.class,
            failure ->
                assertThat(failure.getViolations())
                    .singleElement()
                    .extracting(violation -> violation.field())
                    .isEqualTo("fields.name"));
  }

  private static ApplicationFormDocument document(
      FormReferenceMapping.Mode mode, String detailId, boolean editable) {
    var reference =
        new FormFieldDefinition(
            "customer",
            "customer",
            "Customer",
            "Customer",
            FormFieldType.UUID,
            FormStorageMode.REFERENCE,
            false,
            false,
            null,
            null,
            null,
            null,
            List.of());
    var output =
        new FormFieldDefinition(
            "name",
            "name",
            "Name",
            "Name",
            FormFieldType.STRING,
            FormStorageMode.STORED,
            true,
            false,
            120,
            null,
            null,
            null,
            List.of());
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(detailId, "customer"),
            new FormRelationTarget("app", "customers", "customers-page"),
            null,
            null,
            List.of(
                new FormReferenceMapping(
                    "name",
                    "name",
                    "name",
                    mode,
                    FormReferenceMapping.Conversion.IDENTITY,
                    FormReferenceMapping.NullPolicy.CLEAR)));
    var scene =
        new FormSceneDefinition(
            "main",
            "main",
            "Main",
            FormSceneType.MAIN,
            null,
            new FormComponentDefinition(
                "root",
                "layout.form",
                "1",
                null,
                24,
                null,
                null,
                null,
                null,
                false,
                Map.of(),
                List.of(
                    new FormComponentDefinition(
                        "input",
                        "field.text",
                        "1",
                        "name",
                        24,
                        null,
                        null,
                        null,
                        null,
                        !editable,
                        Map.of(),
                        List.of()))));
    var fields = List.of(reference, output);
    return new ApplicationFormDocument(
        "1",
        detailId == null ? fields : List.of(output),
        List.of(),
        List.of(scene),
        List.of(),
        detailId == null
            ? List.of()
            : List.of(new FormDetailDefinition(detailId, detailId, "Lines", fields, List.of())),
        List.of(),
        null,
        List.of(relation));
  }
}
