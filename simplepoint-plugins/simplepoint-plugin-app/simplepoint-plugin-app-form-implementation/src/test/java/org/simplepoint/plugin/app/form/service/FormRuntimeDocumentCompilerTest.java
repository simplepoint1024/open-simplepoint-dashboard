package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormCondition;
import org.simplepoint.plugin.app.form.api.model.FormConditionOperator;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormFieldWritePolicy;
import org.simplepoint.plugin.app.form.api.model.FormRuleDefinition;
import org.simplepoint.plugin.app.form.api.model.FormRuleEffect;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;

class FormRuntimeDocumentCompilerTest {

  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void removesStorageMetadataAndAppliesEffectiveFieldPolicy() {
    AuthorizationContext authorization = new AuthorizationContext();
    authorization.setResourceFieldPermissions(Map.of(
        "app.runtime.page.one",
        Map.of(
            "dna.runtime.record.model-a#display_name", "VISIBLE",
            "dna.runtime.record.model-a#secret_note", "HIDDEN"
        )
    ));
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY,
        authorization
    );

    var runtime = new FormRuntimeDocumentCompiler().compile(form());

    assertThat(runtime.fields())
        .extracting(field -> field.fieldId())
        .containsExactly("name-field");
    assertThat(runtime.fields().getFirst().writePolicy()).isEqualTo(FormFieldWritePolicy.UNTIL_SUBMITTED);
    assertThat(runtime.scenes()).singleElement().satisfies(scene -> {
      assertThat(scene.root().children())
          .extracting(FormComponentDefinition::componentId)
          .containsExactly("name-control");
      assertThat(scene.root().children().getFirst().readOnly()).isTrue();
    });
    assertThat(runtime.rules()).isEmpty();
  }

  private static PublishedApplicationForm form() {
    FormFieldDefinition name = field("name-field", "display_name");
    FormFieldDefinition secret = field("secret-field", "secret_note");
    FormComponentDefinition root = component("root", null, List.of(
        component("name-control", name.fieldId(), List.of()),
        component("secret-control", secret.fieldId(), List.of())
    ));
    FormRuleDefinition hiddenSourceRule = new FormRuleDefinition(
        "secret-rule",
        "name-control",
        FormRuleEffect.HIDE,
        new FormCondition(
            FormConditionOperator.IS_NOT_EMPTY,
            secret.fieldId(),
            List.of(),
            List.of()
        )
    );
    ApplicationFormDocument document = new ApplicationFormDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(name, secret),
        List.of(),
        List.of(new FormSceneDefinition(
            "main", "main", "Main", FormSceneType.MAIN, null, root
        )),
        List.of(hiddenSourceRule)
    );
    return new PublishedApplicationForm(
        "tenant-a",
        "app-a",
        "page-a",
        "Orders",
        "form-a",
        "Order form",
        1,
        "app.runtime.page.one",
        "model-a",
        1,
        "a".repeat(64),
        document
    );
  }

  @Test
  void maskedFieldsNeverExposeDefaultsChoicesOrRulesThatDependOnTheirOriginalValues() {
    var authorization = new AuthorizationContext();
    authorization.setResourceFieldPermissions(Map.of("app.runtime.page.one",
        Map.of("dna.runtime.record.model-a#secret_note", "MASKED")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    var source = form();
    var secret = new FormFieldDefinition("secret-field", "secret_note", "Secret", "Secret",
        FormFieldType.STRING, FormStorageMode.STORED, false, false, 120, null, null,
        new FormValue(FormValueKind.STRING, "protected-default"),
        List.of(new FormChoiceOption("protected-default", "Secret classification")));
    var document = new ApplicationFormDocument("1", List.of(source.document().fields().getFirst(), secret),
        List.of(), source.document().scenes(), source.document().rules());
    var projected = new FormRuntimeDocumentCompiler().compile(new PublishedApplicationForm(
        source.tenantId(), source.applicationId(), source.pageId(), source.pageName(), source.formId(),
        source.formName(), source.formVersion(), source.resourceCode(), source.physicalModelId(),
        source.physicalModelVersion(), source.storageChecksum(), document));
    var masked = projected.fields().stream().filter(field -> field.fieldId().equals("secret-field")).findFirst().orElseThrow();
    assertThat(masked.defaultValue()).isNull();
    assertThat(masked.choices()).isEmpty();
    assertThat(projected.rules()).isEmpty();
    assertThat(projected.scenes().getFirst().root().children().getLast().readOnly()).isTrue();
    assertThat(document.fields().getLast().defaultValue().value()).isEqualTo("protected-default");
  }

  @Test
  void visibleReadOnlyDependenciesRetainRulesBecauseTheirOriginalValuesAreAuthorized() {
    var authorization = new AuthorizationContext();
    authorization.setResourceFieldPermissions(Map.of("app.runtime.page.one",
        Map.of("dna.runtime.record.model-a#secret_note", "VISIBLE")));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    var runtime = new FormRuntimeDocumentCompiler().compile(form());
    assertThat(runtime.rules()).hasSize(1);
    assertThat(runtime.scenes().getFirst().root().children().getLast().readOnly()).isTrue();
  }

  private static FormFieldDefinition field(final String fieldId, final String storageCode) {
    return new FormFieldDefinition(
        fieldId,
        storageCode,
        fieldId,
        fieldId,
        FormFieldType.STRING,
        FormStorageMode.STORED,
        false,
        false,
        120,
        null,
        null,
        null,
        List.of(),
        FormFieldWritePolicy.UNTIL_SUBMITTED
    );
  }

  private static FormComponentDefinition component(
      final String componentId,
      final String fieldId,
      final List<FormComponentDefinition> children
  ) {
    return new FormComponentDefinition(
        componentId,
        fieldId == null ? "layout.form" : "field.text",
        ApplicationFormApiVersion.RENDERER_V1,
        fieldId,
        24,
        null,
        null,
        null,
        null,
        false,
        Map.of(),
        children
    );
  }
}
