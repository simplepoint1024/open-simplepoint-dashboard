package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormCalculation;
import org.simplepoint.plugin.app.form.api.model.FormComponentDefinition;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormSceneDefinition;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

class FormCalculationPermissionsTest {
  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void hiddenOrMaskedSourcesProtectEveryTransitiveResultAcrossMainAndDetailScopes() {
    for (String level : List.of("HIDDEN", "MASKED")) {
      var base = new LinkedHashMap<String, String>();
      base.put(key("main-model", "rate"), level);
      base.put(key("main-model", "total"), "EDITABLE");
      var result = FormCalculationPermissions.restrict(form(), base);
      assertThat(result)
          .containsEntry(key("main-model", "rate"), level)
          .containsEntry(key("line-model", "amount"), "HIDDEN")
          .containsEntry(key("main-model", "total"), "HIDDEN")
          .containsEntry(key("main-model", "summary"), "HIDDEN")
          .containsEntry(key("main-model", "constant"), "VISIBLE");
      assertThat(base).hasSize(2).doesNotContainKey(key("line-model", "amount"));
      assertThatThrownBy(() -> result.put("modified", "EDITABLE"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void respectsLegacyColumnRestrictionsAndTheDerivedFieldsOwnGrants() {
    var legacy =
        Map.of(
            "dna.runtime.record.line-model#column_price",
            "MASKED",
            key("line-model", "price"),
            "EDITABLE");
    assertThat(FormCalculationPermissions.restrict(form(), legacy))
        .containsEntry(key("main-model", "total"), "HIDDEN");
    var result =
        FormCalculationPermissions.restrict(form(), Map.of(key("main-model", "total"), "MASKED"));
    assertThat(result)
        .containsEntry(key("main-model", "total"), "MASKED")
        .containsEntry(key("main-model", "summary"), "HIDDEN")
        .containsEntry(key("line-model", "amount"), "VISIBLE");
  }

  @Test
  void readableSourcesAllowReadOnlyResultsWithoutRequiringWriteAccess() {
    var result =
        FormCalculationPermissions.restrict(
            form(),
            Map.of(key("main-model", "rate"), "VISIBLE", key("line-model", "price"), "VISIBLE"));
    assertThat(result)
        .containsEntry(key("main-model", "total"), "VISIBLE")
        .containsEntry(key("main-model", "summary"), "VISIBLE")
        .containsEntry(key("line-model", "amount"), "VISIBLE");
  }

  @Test
  void runtimeAndDnaInvocationUseTheSameRestrictionsWithoutMutatingAuthorization() {
    var form = form();
    var base = Map.of(key("line-model", "price"), "MASKED");
    var authorization = new AuthorizationContext();
    authorization.setUserId("reviewer");
    authorization.setAttributes(Map.of("X-Tenant-Id", "tenant", "X-Org-Dept-Id", "department"));
    authorization.setResources(Set.of(form.resourceCode()));
    authorization.setResourceFieldPermissions(Map.of(form.resourceCode(), base));
    authorization.setResourceDataScopes(
        Map.of(
            form.resourceCode(),
            new ResourceDataScope("SELF", Set.of("department"), true, List.of())));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
    var context = new DnaInvocationContextFactory(new FormRequestContext()).forForm(form);
    assertThat(context.fieldPermissions()).containsEntry(key("main-model", "total"), "HIDDEN");
    assertThat(context.actorId()).isEqualTo("reviewer");
    assertThat(context.dataScopeType()).isEqualTo("SELF");
    assertThat(context.departmentIds()).containsExactly("department");
    assertThat(context.resources()).containsExactly(form.resourceCode());
    var runtime = new FormRuntimeDocumentCompiler().compile(form);
    assertThat(runtime.fields())
        .extracting(field -> field.fieldId())
        .containsExactly("rate", "constant");
    assertThat(runtime.scenes().getFirst().root().children())
        .extracting(component -> component.fieldId())
        .containsExactly("rate", "constant");
    assertThat(runtime.scenes().getFirst().root().children().getLast().readOnly()).isTrue();
    assertThat(runtime.details().getFirst().fields())
        .extracting(field -> field.fieldId())
        .containsExactly("price");
    assertThat(authorization.getResourceFieldPermissions().get(form.resourceCode())).isSameAs(base);
  }

  @Test
  void currentDependenciesMayReferenceNewFieldsWithoutAddingThemToAnOldPage() throws Exception {
    var source = form();
    var page = new ApplicationFormDocument("1", source.document().fields().stream().filter(field -> !field.fieldId().equals("rate")).toList(),
        List.of(), source.document().scenes(), List.of(), source.document().details());
    var historical = new PublishedApplicationForm(source.tenantId(), source.applicationId(), source.pageId(), source.pageName(),
        source.formId(), source.formName(), 1, source.resourceCode(), source.physicalModelId(), 1, source.storageChecksum(),
        page, 2, 2, source.detailBindings(), source.document());
    assertThat(FormCalculationPermissions.restrict(historical, Map.of(key("main-model", "rate"), "MASKED")))
        .containsEntry(key("main-model", "summary"), "HIDDEN").containsEntry(key("main-model", "total"), "HIDDEN");
    assertThat(FormCalculationPermissions.restrict(historical, Map.of()))
        .containsEntry(key("main-model", "summary"), "VISIBLE");
    assertThat(historical.document().fields()).noneMatch(field -> field.fieldId().equals("rate"));
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsString(historical), PublishedApplicationForm.class)).isEqualTo(historical);
    assertThat(mapper.writeValueAsString(source)).doesNotContain("calculationDocument");
  }

  @Test
  void missingPublishedScopeCannotFallBackToUnrestrictedAccess() {
    var source = form();
    var missing =
        new PublishedApplicationForm(
            source.tenantId(),
            source.applicationId(),
            source.pageId(),
            source.pageName(),
            source.formId(),
            source.formName(),
            1,
            source.resourceCode(),
            source.physicalModelId(),
            1,
            source.storageChecksum(),
            source.document());
    assertThatThrownBy(() -> FormCalculationPermissions.restrict(missing, Map.of()))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static String key(String model, String field) {
    return RecordPermissionModel.key(model) + "#" + RecordPermissionModel.field(field);
  }

  private static PublishedApplicationForm form() {
    var fields =
        List.of(
            field("rate", null),
            field("total", "SUM([lines.amount])"),
            field("summary", "[total]*2"),
            field("constant", "1"));
    var details =
        List.of(
            new FormDetailDefinition(
                "lines",
                "expense_lines",
                "明细",
                List.of(field("price", null), field("amount", "ROUND([price]*[parent.rate],2)")),
                List.of()));
    var root =
        component(
            "root",
            null,
            fields.stream()
                .map(field -> component(field.fieldId() + "-input", field.fieldId(), List.of()))
                .toList());
    var document =
        new ApplicationFormDocument(
            "1",
            fields,
            List.of(),
            List.of(
                new FormSceneDefinition("main", "main", "Main", FormSceneType.MAIN, null, root)),
            List.of(),
            details);
    return new PublishedApplicationForm(
        "tenant",
        "app",
        "page",
        "Page",
        "form",
        "Form",
        1,
        "app.runtime.page",
        "main-model",
        1,
        "a".repeat(64),
        document,
        1,
        0,
        List.of(new FormDetailBinding("lines", "line-model", 1, "b".repeat(64))));
  }

  private static FormFieldDefinition field(String id, String expression) {
    return new FormFieldDefinition(
        id,
        "column_" + id,
        id,
        id,
        FormFieldType.DECIMAL,
        expression == null ? FormStorageMode.STORED : FormStorageMode.COMPUTED,
        false,
        false,
        null,
        18,
        2,
        null,
        List.of(),
        null,
        null,
        null,
        expression == null ? null : new FormCalculation("1", expression));
  }

  private static FormComponentDefinition component(
      String id, String field, List<FormComponentDefinition> children) {
    return new FormComponentDefinition(
        id,
        field == null ? "layout.form" : "field.number",
        "1",
        field,
        24,
        null,
        null,
        null,
        null,
        false,
        Map.of(),
        children);
  }
}
