package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormDetailDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormPresentation;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseRelationView;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

class FormInversePublicationTest {
  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void validatesExactTargetAndReadableNullableRootOrOwnedSource(boolean owned) {
    var actors = mock(FormRequestContext.class);
    when(actors.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant", "designer"));
    var resolver =
        spy(
            new FormRelationTargetResolver(
                mock(EntityManager.class), actors, mock(PublishedApplicationFormResolver.class)));
    var identity = new FormRelationTarget("sales", "trades", "entry");
    var relation =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(owned ? "lines" : null, "customer"),
            new FormRelationTarget("crm", "customers", "customers"));
    var field =
        new FormFieldDefinition(
            "customer",
            "renamed_customer",
            "客户",
            "客户",
            FormFieldType.UUID,
            FormStorageMode.REFERENCE,
            false,
            false,
            null,
            null,
            null,
            null,
            List.of());
    var sourceDocument =
        new ApplicationFormDocument(
            "1",
            owned ? List.of() : List.of(field),
            List.of(),
            List.of(),
            List.of(),
            owned
                ? List.of(
                    new FormDetailDefinition("lines", "lines", "明细", List.of(field), List.of()))
                : List.of(),
            List.of(),
            null,
            List.of(relation));
    var source =
        new PublishedApplicationForm(
            "tenant",
            "sales",
            "trades",
            "交易",
            "trades",
            "交易",
            2,
            "sales.resource",
            "trade-model",
            1,
            "a".repeat(64),
            sourceDocument,
            2,
            2,
            owned
                ? List.of(new FormDetailBinding("lines", "line-model", 1, "b".repeat(64)))
                : List.of());
    var parentDocument =
        new ApplicationFormDocument(
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new FormPresentation(
                List.of(),
                140,
                List.of(
                    new FormInverseRelationView(
                        "trades", "交易", identity, "customer", true, true, true, true, null))));
    var parent =
        new PublishedApplicationForm(
            "tenant",
            "crm",
            "customers",
            "客户",
            "customers",
            "客户",
            1,
            "crm.resource",
            "customer-model",
            1,
            "c".repeat(64),
            parentDocument);
    doReturn(source).when(resolver).describeTarget(identity);
    doReturn(new FormRelationTargetResolver.ResolvedRelation(relation, parent))
        .when(resolver)
        .resolve(source, "customer");
    policy(Map.of());
    resolver.validateInverseViews("crm", "customers", "customer-model", parentDocument);
    assertThatThrownBy(
            () -> resolver.validateInverseViews("crm", "other", "customer-model", parentDocument))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(
            () ->
                resolver.validateInverseViews(
                    "crm", "customers", "replacement-model", parentDocument))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(
            () -> resolver.validateInverseViews("crm", "customers", null, parentDocument))
        .isInstanceOf(ApplicationFormException.class);
    for (String access : List.of("HIDDEN", "MASKED")) {
      policy(
          Map.of(
              RecordPermissionModel.key(owned ? "line-model" : "trade-model")
                  + "#"
                  + RecordPermissionModel.field("customer"),
              access));
      assertThatThrownBy(
              () ->
                  resolver.validateInverseViews(
                      "crm", "customers", "customer-model", parentDocument))
          .isInstanceOf(ApplicationFormException.class);
    }
    policy(Map.of());
    var required =
        new FormFieldDefinition(
            "customer",
            "renamed_customer",
            "客户",
            "客户",
            FormFieldType.UUID,
            FormStorageMode.REFERENCE,
            false,
            true,
            null,
            null,
            null,
            null,
            List.of());
    assertThat(required.storageRequired()).isTrue();
    var requiredDocument =
        new ApplicationFormDocument(
            "1",
            owned ? List.of() : List.of(required),
            List.of(),
            List.of(),
            List.of(),
            owned
                ? List.of(
                    new FormDetailDefinition("lines", "lines", "明细", List.of(required), List.of()))
                : List.of(),
            List.of(),
            null,
            List.of(relation));
    var requiredSource =
        new PublishedApplicationForm(
            source.tenantId(),
            source.applicationId(),
            source.pageId(),
            source.pageName(),
            source.formId(),
            source.formName(),
            source.formVersion(),
            source.resourceCode(),
            source.physicalModelId(),
            source.physicalModelVersion(),
            source.storageChecksum(),
            requiredDocument,
            source.writePolicyVersion(),
            source.writeAdmissionVersion(),
            source.detailBindings());
    doReturn(requiredSource).when(resolver).describeTarget(identity);
    doReturn(new FormRelationTargetResolver.ResolvedRelation(relation, parent))
        .when(resolver)
        .resolve(requiredSource, "customer");
    assertThatThrownBy(
            () ->
                resolver.validateInverseViews("crm", "customers", "customer-model", parentDocument))
        .isInstanceOf(ApplicationFormException.class);
    clear();
    assertThatThrownBy(
            () ->
                resolver.validateInverseViews("crm", "customers", "customer-model", parentDocument))
        .isInstanceOf(ApplicationFormException.class);
  }

  private static void policy(Map<String, String> fields) {
    var context = new AuthorizationContext();
    context.setUserId("designer");
    context.setResourceFieldPermissions(Map.of("sales.resource", fields));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }
}
