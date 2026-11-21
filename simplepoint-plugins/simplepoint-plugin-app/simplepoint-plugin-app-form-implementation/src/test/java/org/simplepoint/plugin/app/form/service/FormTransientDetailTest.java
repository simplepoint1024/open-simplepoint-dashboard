package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormIndexDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMapping;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;

class FormTransientDetailTest {
  private final FormDocumentValidator validator =
      new FormDocumentValidator(new DefaultFormComponentRegistry());

  @Test
  void transientDetailCanBeDesignedBeforeBindingItsLiveProviderWithoutPhysicalColumns() {
    for (boolean mapped : List.of(false, true)) {
      var document = document(mapped, false, false);
      validator.validatePublication(document);
      var detail = document.details().getFirst();
      var storage = new FormStorageProjectionCompiler(new ObjectMapper());
      var bindings =
          List.of(
              new FormRelationBinding(
                  "customer",
                  document.relations().getFirst().target(),
                  "customers-page",
                  "customers",
                  "customers-model"));
      var physical = storage.compileDetail(document, detail, "parent-model", bindings);
      assertThat(physical.fieldCodes()).containsOnlyKeys("reference");
      assertThat(physical.schema().fields())
          .extracting(field -> field.fieldId())
          .containsExactly("owned_parent", "owned_order", "reference");
      assertThat(FormCalculationValidationProjection.compile(document)).isEmpty();
      assertThat(FormCalculationWriteProjection.compile(document)).isEmpty();
    }
  }

  @Test
  void temporaryFieldsExposeStableReadGrantsWithoutQueryOperatorsOrWriteGrants() {
    var fields = document(true, false, false).details().getFirst().fields();
    var model = FormPermissionDescriptorService.model("detail-model", fields, "Lines / ");
    var field =
        model
            .properties()
            .get(org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel.field("live"));
    assertThat(field).isNotNull();
    assertThat(field.readOnly()).isTrue();
    assertThat(field.operators()).isEmpty();
    assertThat(field.title()).isEqualTo("Lines / Current name");
    assertThat(model.properties()).doesNotContainKey("live");
  }

  @Test
  void addingOrRemovingTemporaryFieldsDoesNotMigrateOrRelaxExistingOwnedColumns() {
    var withLive = document(true, false, false);
    var detail = withLive.details().getFirst();
    var withoutLive =
        new ApplicationFormDocument(
            "1",
            List.of(),
            List.of(),
            withLive.scenes(),
            List.of(),
            List.of(
                new FormDetailDefinition(
                    detail.detailId(),
                    detail.storageCode(),
                    detail.label(),
                    detail.fields().stream().filter(FormFieldDefinition::persistent).toList(),
                    List.of())));
    var compiler = new FormStorageProjectionCompiler(new ObjectMapper());
    FormDetailEvolutionPolicy.validate(withoutLive, withLive, compiler);
    FormDetailEvolutionPolicy.validate(withLive, withoutLive, compiler);
    assertThat(compiler.compileDetail(withLive.details().getFirst(), "parent").checksum())
        .isEqualTo(compiler.compileDetail(withoutLive.details().getFirst(), "parent").checksum());
    assertThat(withoutLive.details().getFirst().fields()).hasSize(1);
  }

  @Test
  void temporaryColumnsCannotAcquireIndexesOrPersistentRequirements() {
    assertInvalid(() -> validator.validatePublication(document(true, true, false)));
    assertInvalid(() -> validator.validatePublication(document(true, false, true)));
  }

  @Test
  void liveDisplayCannotBeForgedIntoAnOwnedWrite() {
    var document = document(true, false, false);
    var detail = document.details().getFirst();
    var scalar = new ApplicationFormDocument("1", detail.fields(), List.of(), List.of(), List.of());
    var payload = new FormRecordPayloadCompiler();
    var providers = FormRecordValueProviders.forRow(document, "lines", 0, null);
    assertThatThrownBy(
            () ->
                payload.compile(
                    scalar,
                    Map.of(),
                    Map.of("live", "forged"),
                    null,
                    FormRecordState.DRAFT,
                    providers))
        .isInstanceOf(ApplicationFormException.class);
    assertThat(payload.compile(scalar, Map.of(), Map.of(), null, FormRecordState.DRAFT, providers))
        .doesNotContainKey("live")
        .doesNotContainKey(null);
  }

  private static void assertInvalid(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.DOCUMENT_INVALID));
  }

  private static ApplicationFormDocument document(
      boolean mapped, boolean indexed, boolean required) {
    var reference =
        new FormFieldDefinition(
            "reference",
            "reference",
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
    var live =
        new FormFieldDefinition(
            "live",
            null,
            "Current name",
            null,
            FormFieldType.STRING,
            FormStorageMode.TRANSIENT,
            required,
            false,
            120,
            null,
            null,
            null,
            List.of());
    var details =
        new FormDetailDefinition(
            "lines",
            "lines",
            "Lines",
            List.of(reference, live),
            indexed
                ? List.of(
                    new FormIndexDefinition("live-index", "live_index", false, List.of("live")))
                : List.of());
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField("lines", "reference"),
            new FormRelationTarget("app", "customers", "customers-page"),
            null,
            null,
            mapped
                ? List.of(
                    new FormReferenceMapping(
                        "live",
                        "name",
                        "live",
                        FormReferenceMapping.Mode.LIVE,
                        FormReferenceMapping.Conversion.IDENTITY,
                        FormReferenceMapping.NullPolicy.CLEAR))
                : List.of());
    var root =
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
            List.of());
    return new ApplicationFormDocument(
        "1",
        List.of(),
        List.of(),
        List.of(new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)),
        List.of(),
        List.of(details),
        List.of(),
        null,
        List.of(relation));
  }
}
