package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormChoiceOption;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.FormValue;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;

class ApplicationFormRecordSchemaServiceImplTest {

  @Test
  void readersCanInspectRecordsWithoutReceivingWriteOrSubmissionActions() {
    var resolver = mock(PublishedApplicationFormResolver.class);
    var documents = mock(FormRuntimeDocumentCompiler.class);
    var contexts = mock(DnaInvocationContextFactory.class);
    var context = mock(DnaInvocationContext.class);
    var published = form();
    when(resolver.resolve("app-a", "page-a")).thenReturn(published);
    when(resolver.capabilities(published)).thenReturn(
        new ApplicationFormRuntimeCapabilities(false, false, false, false));
    when(contexts.forResource("app.runtime.page.one")).thenReturn(context);
    when(context.fieldPermissions()).thenReturn(Map.of());
    when(documents.compile(published)).thenReturn(new ApplicationFormRuntimeDocument(
        "1", List.of(), List.of(), List.of()));
    var service = new ApplicationFormRecordSchemaServiceImpl(resolver, documents, contexts);
    var buttons = (List<?>) service.schema("app-a", "page-a").get("buttons");
    assertThat(buttons).extracting(button -> String.valueOf(((Map<?, ?>) button).get("key")))
        .containsExactly("view");

    when(resolver.capabilities(published)).thenReturn(
        new ApplicationFormRuntimeCapabilities(false, false, false, true));
    buttons = (List<?>) service.schema("app-a", "page-a").get("buttons");
    assertThat(buttons).extracting(button -> String.valueOf(((Map<?, ?>) button).get("key")))
        .containsExactly("view", "history");
  }

  @Test
  @SuppressWarnings("unchecked")
  void exposesSimpleTableSchemaWithAuthorizedActionsAndStableFieldIdentities() {
    PublishedApplicationFormResolver resolver = mock(PublishedApplicationFormResolver.class);
    FormRuntimeDocumentCompiler documents = mock(FormRuntimeDocumentCompiler.class);
    DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
    DnaInvocationContext context = mock(DnaInvocationContext.class);
    PublishedApplicationForm form = form();
    when(resolver.resolve("app-a", "page-a")).thenReturn(form);
    when(resolver.capabilities(form)).thenReturn(
        new ApplicationFormRuntimeCapabilities(true, false, true, false)
    );
    when(contexts.forResource("app.runtime.page.one")).thenReturn(context);
    when(context.fieldPermissions()).thenReturn(Map.of());
    when(documents.compile(form)).thenReturn(new ApplicationFormRuntimeDocument(
        ApplicationFormApiVersion.DOCUMENT_V1,
        List.of(new ApplicationFormRuntimeFieldDefinition(
            "name-field",
            "Customer name",
            FormFieldType.STRING,
            true,
            120,
            null,
            null,
            null,
            List.of()
        )),
        List.of(),
        List.of()
    ));

    Map<String, Object> result = new ApplicationFormRecordSchemaServiceImpl(
        resolver,
        documents,
        contexts
    ).schema("app-a", "page-a");

    Map<String, Object> schema = (Map<String, Object>) result.get("schema");
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat((Map<String, Object>) properties.get("name-field"))
        .containsEntry("readOnly", true).containsEntry("x-display-only", true);
    assertThat(properties.keySet()).containsExactly(
        "id", "version", "recordState", "tenantId", "createOrgDeptId", "createdBy", "createdAt", "updatedBy", "updatedAt", "deletedAt", "name-field"
    );
    Map<String, Object> query = (Map<String, Object>) schema.get("x-query");
    assertThat((Map<String, Object>) query.get("fields"))
        .containsKeys("recordState", "name-field")
        .doesNotContainKeys("id", "version");
    assertThat((List<Map<String, Object>>) result.get("buttons"))
        .extracting(button -> button.get("key"))
        .containsExactly("view", "add", "delete");
  }

  @Test
  void recordTableChoicesUseTheSameTypedValuesAsRuntimeForms() {
    var resolver = mock(PublishedApplicationFormResolver.class);
    var documents = mock(FormRuntimeDocumentCompiler.class);
    var contexts = mock(DnaInvocationContextFactory.class);
    var context = mock(DnaInvocationContext.class);
    var fields = List.of(
        choiceField("amount", FormFieldType.DECIMAL, "9999999999999999999.123456780"),
        choiceField("approved", FormFieldType.BOOLEAN, "false"),
        choiceField("serial", FormFieldType.INT64, "9223372036854775807")
    );
    var base = form();
    var form = new PublishedApplicationForm(base.tenantId(), base.applicationId(), base.pageId(),
        base.pageName(), base.formId(), base.formName(), base.formVersion(), base.resourceCode(),
        base.physicalModelId(), base.physicalModelVersion(), base.storageChecksum(),
        new ApplicationFormDocument("1", fields.stream().map(field -> new FormFieldDefinition(
            field.fieldId(), field.fieldId(), field.label(), field.label(), field.type(),
            FormStorageMode.STORED, false, false, field.length(), field.precision(), field.scale(),
            field.defaultValue(), field.choices()
        )).toList(), List.of(), List.of(), List.of()));
    when(resolver.resolve("app-a", "page-a")).thenReturn(form);
    when(resolver.capabilities(form)).thenReturn(
        new ApplicationFormRuntimeCapabilities(true, true, true, false));
    when(contexts.forResource("app.runtime.page.one")).thenReturn(context);
    when(context.fieldPermissions()).thenReturn(Map.of());
    when(documents.compile(form)).thenReturn(new ApplicationFormRuntimeDocument(
        "1", fields, List.of(), List.of()));
    var result = new ApplicationFormRecordSchemaServiceImpl(resolver, documents, contexts)
        .schema("app-a", "page-a");
    var properties = (Map<?, ?>) ((Map<?, ?>) result.get("schema")).get("properties");
    var amount = (Map<?, ?>) properties.get("amount");
    assertThat(amount.get("type")).isEqualTo(List.of("string", "null"));
    assertThat(((Map<?, ?>) ((List<?>) amount.get("oneOf")).getFirst()).get("const"))
        .isEqualTo("9999999999999999999.12345678");
    assertThat(((Map<?, ?>) ((List<?>) amount.get("oneOf")).getLast()).containsKey("const")).isTrue();
    assertThat(((Map<?, ?>) ((List<?>) amount.get("oneOf")).getLast()).get("const")).isNull();
    var approved = (Map<?, ?>) properties.get("approved");
    assertThat(approved.get("type")).isEqualTo(List.of("boolean", "null"));
    assertThat(((Map<?, ?>) ((List<?>) approved.get("oneOf")).getFirst()).get("const"))
        .isEqualTo(false);
    var serial = (Map<?, ?>) properties.get("serial");
    assertThat(((Map<?, ?>) ((List<?>) serial.get("oneOf")).getFirst()).get("const"))
        .isEqualTo("9223372036854775807");
  }

  @Test
  void numericAndJsonInputsUseLosslessPlatformContracts() {
    var resolver = mock(PublishedApplicationFormResolver.class);
    var documents = mock(FormRuntimeDocumentCompiler.class);
    var contexts = mock(DnaInvocationContextFactory.class);
    var context = mock(DnaInvocationContext.class);
    var fields = List.of(
        new ApplicationFormRuntimeFieldDefinition("amount", "Amount", FormFieldType.DECIMAL, false,
            null, 38, 18, new FormValue(FormValueKind.DECIMAL, "12345678901234567890.123456789012345678"), List.of()),
        new ApplicationFormRuntimeFieldDefinition("serial", "Serial", FormFieldType.INT64, false,
            null, null, null, new FormValue(FormValueKind.INTEGER, "9223372036854775807"), List.of()),
        new ApplicationFormRuntimeFieldDefinition("payload", "Payload", FormFieldType.JSON, false,
            null, null, null, new FormValue(FormValueKind.JSON, "9007199254740993"), List.of())
    );
    var base = form();
    var form = new PublishedApplicationForm(base.tenantId(), base.applicationId(), base.pageId(),
        base.pageName(), base.formId(), base.formName(), base.formVersion(), base.resourceCode(),
        base.physicalModelId(), base.physicalModelVersion(), base.storageChecksum(),
        new ApplicationFormDocument("1", fields.stream().map(field -> new FormFieldDefinition(
            field.fieldId(), field.fieldId(), field.label(), field.label(), field.type(),
            FormStorageMode.STORED, false, false, field.length(), field.precision(), field.scale(),
            field.defaultValue(), field.choices()
        )).toList(), List.of(), List.of(), List.of()));
    when(resolver.resolve("app-a", "page-a")).thenReturn(form);
    when(resolver.capabilities(form)).thenReturn(new ApplicationFormRuntimeCapabilities(true, true, true, false));
    when(contexts.forResource("app.runtime.page.one")).thenReturn(context);
    when(context.fieldPermissions()).thenReturn(Map.of());
    when(documents.compile(form)).thenReturn(new ApplicationFormRuntimeDocument("1", fields, List.of(), List.of()));
    var result = new ApplicationFormRecordSchemaServiceImpl(resolver, documents, contexts).schema("app-a", "page-a");
    var properties = (Map<?, ?>) ((Map<?, ?>) result.get("schema")).get("properties");
    var amount = (Map<?, ?>) properties.get("amount");
    var serial = (Map<?, ?>) properties.get("serial");
    var payload = (Map<?, ?>) properties.get("payload");
    assertThat(payload.get("x-json-value")).isEqualTo(true);
    assertThat(payload.get("type")).isEqualTo(List.of("string", "null"));
    assertThat(payload.get("x-ui")).isEqualTo(Map.of("x-list-visible", true, "widget", "textarea", "format", "json"));
    assertThat(payload.get("default")).isEqualTo("9007199254740993");
    assertThat(amount.get("type")).isEqualTo(List.of("string", "null"));
    assertThat(amount.get("default")).isEqualTo("12345678901234567890.123456789012345678");
    assertThat(((Map<?, ?>) amount.get("x-decimal")).get("maximum"))
        .isEqualTo("99999999999999999999.999999999999999999");
    assertThat(serial.get("default")).isEqualTo("9223372036854775807");
    assertThat(((Map<?, ?>) serial.get("x-decimal")).get("minimum")).isEqualTo("-9223372036854775808");
  }

  @Test
  void publishedListOrderUsesStableIdentitiesWithoutChangingSceneOrder() {
    var original = form();
    var first = original.document().fields().getFirst();
    var second = new FormFieldDefinition("second-field", "second_value", "Second", "Second", FormFieldType.STRING,
        FormStorageMode.STORED, false, false, 120, null, null, null, List.of());
    var document = new ApplicationFormDocument("1", List.of(first, second), List.of(), List.of(), List.of(), List.of(), List.of(),
        new org.simplepoint.plugin.app.form.api.model.FormPresentation(List.of("second-field", "name-field"), 120));
    var form = new PublishedApplicationForm("tenant-a", "app-a", "page-a", "Orders", "form-a", "Order form", 1,
        "app.runtime.page.one", "model-a", 1, "a".repeat(64), document);
    var resolver = mock(PublishedApplicationFormResolver.class);
    var documents = mock(FormRuntimeDocumentCompiler.class);
    var contexts = mock(DnaInvocationContextFactory.class);
    var context = mock(DnaInvocationContext.class);
    when(resolver.resolve("app-a", "page-a")).thenReturn(form);
    when(resolver.capabilities(form)).thenReturn(new ApplicationFormRuntimeCapabilities(true, true, false, false));
    when(contexts.forResource("app.runtime.page.one")).thenReturn(context);
    when(context.fieldPermissions()).thenReturn(Map.of());
    when(documents.compile(form)).thenReturn(new ApplicationFormRuntimeDocument("1", List.of(
        choiceField("name-field", FormFieldType.STRING, "a"), choiceField("second-field", FormFieldType.STRING, "b")), List.of(), List.of()));
    var schema = new ApplicationFormRecordSchemaServiceImpl(resolver, documents, contexts).schema("app-a", "page-a");
    var properties = (Map<?, ?>) ((Map<?, ?>) schema.get("schema")).get("properties");
    assertThat((Integer) ((Map<?, ?>) properties.get("second-field")).get("x-order"))
        .isLessThan((Integer) ((Map<?, ?>) properties.get("name-field")).get("x-order"));
    assertThat(document.fields()).extracting(FormFieldDefinition::fieldId).containsExactly("name-field", "second-field");
  }

  private static ApplicationFormRuntimeFieldDefinition choiceField(
      final String id, final FormFieldType type, final String value
  ) {
    return new ApplicationFormRuntimeFieldDefinition(id, id, type, false, null,
        type == FormFieldType.DECIMAL ? 30 : null, type == FormFieldType.DECIMAL ? 8 : null,
        null, List.of(new FormChoiceOption(value, value)));
  }

  private static PublishedApplicationForm form() {
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
        new ApplicationFormDocument(
            ApplicationFormApiVersion.DOCUMENT_V1,
            List.of(new FormFieldDefinition(
                "name-field",
                "customer_name",
                "Customer name",
                "Customer name",
                FormFieldType.STRING,
                FormStorageMode.STORED,
                true,
                false,
                120,
                null,
                null,
                null,
                List.of()
            )),
            List.of(),
            List.of(),
            List.of()
        )
    );
  }
}
