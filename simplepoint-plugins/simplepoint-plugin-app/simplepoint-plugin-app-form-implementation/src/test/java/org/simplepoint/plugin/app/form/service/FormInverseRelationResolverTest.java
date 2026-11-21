package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordFilter;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordFilterOperator;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormPresentation;
import org.simplepoint.plugin.app.form.api.model.FormRecordState;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseRelationView;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingConfirmation;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingIntent;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingObservation;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

class FormInverseRelationResolverTest {
  private static final String RECORD = "aaaaaaaa-1111-2222-3333-000000000001";
  private static final ApplicationFormRuntimeIdentity RUNTIME =
      new ApplicationFormRuntimeIdentity("customers", 1);
  private final PublishedApplicationFormResolver forms =
      mock(PublishedApplicationFormResolver.class);
  private final FormRelationTargetResolver relations = mock(FormRelationTargetResolver.class);
  private final ApplicationFormRecordServiceImpl records =
      mock(ApplicationFormRecordServiceImpl.class);
  private final DnaInvocationContextFactory contexts = mock(DnaInvocationContextFactory.class);
  private final DnaInvocationContext policy = mock(DnaInvocationContext.class);
  private final FormInverseRelationResolver resolver =
      new FormInverseRelationResolver(forms, relations, records, contexts);
  private final FormInverseRelationView view =
      new FormInverseRelationView(
          "transactions",
          "交易记录",
          new FormRelationTarget("trades", "trades", "trade-entry"),
          "customer",
          true,
          true,
          true,
          true,
          null);
  private PublishedApplicationForm parent;
  private PublishedApplicationForm source;
  private FormRelationDefinition owning;

  @BeforeEach
  void setup() {
    parent =
        form(
            "customers",
            "customer-page",
            "customer-resource",
            new ApplicationFormDocument(
                "1",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new FormPresentation(List.of(), null, List.of(view))));
    owning =
        new FormRelationDefinition(
            "1",
            "customer",
            FormRelationCardinality.MANY_TO_ONE,
            new FormRelationField(null, "customer"),
            new FormRelationTarget("customers", "customers", "customer-entry"));
    var field =
        new FormFieldDefinition(
            "customer",
            "customer_id",
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
    source =
        form(
            "trades",
            "trade-page",
            "trade-resource",
            new ApplicationFormDocument(
                "1",
                List.of(field),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(owning)));
    when(forms.resolve("customers", "customer-page")).thenReturn(parent);
    when(relations.describeTarget(view.source())).thenReturn(source);
    when(relations.resolve(source, "customer"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(owning, parent));
    when(contexts.forForm(source)).thenReturn(policy);
    when(policy.fieldPermissions()).thenReturn(Map.of());
  }

  @Test
  void catalogOmitsDeniedEntriesButNeverHidesInfrastructureOrParentFailures() {
    assertThat(resolver.views("customers", "customer-page", RUNTIME, RECORD))
        .extracting(item -> item.viewId())
        .containsExactly("transactions");
    verify(records).get(parent, RECORD);
    doThrow(new ApplicationFormException(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED, "Denied"))
        .when(relations)
        .describeTarget(view.source());
    assertThat(resolver.views("customers", "customer-page", RUNTIME, RECORD)).isEmpty();
    doThrow(
            new ApplicationFormException(
                ApplicationFormErrorCode.RUNTIME_UNAVAILABLE, "Unavailable"))
        .when(relations)
        .describeTarget(view.source());
    assertThatThrownBy(() -> resolver.views("customers", "customer-page", RUNTIME, RECORD))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ApplicationFormErrorCode.RUNTIME_UNAVAILABLE));
    doThrow(new ApplicationFormException(ApplicationFormErrorCode.RECORD_NOT_FOUND, "Unavailable"))
        .when(records)
        .get(parent, RECORD);
    assertThatThrownBy(() -> resolver.views("customers", "customer-page", RUNTIME, RECORD))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_NOT_FOUND));
  }

  @Test
  void runtimeCannotAdvertiseReadOnlyForeignKeyCreationOrUnlink() {
    var runtime = mock(ApplicationFormRuntimeServiceImpl.class);
    var all =
        new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities(
            true, true, true, true);
    when(forms.capabilities(source)).thenReturn(all);
    var document =
        new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeDocument(
            "1", List.of(), List.of(), List.of());
    when(runtime.describe(source, null))
        .thenReturn(
            new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView(
                source.applicationId(),
                source.pageId(),
                "Trades",
                source.formId(),
                "Trades",
                1,
                "main",
                source.resourceCode(),
                all,
                document,
                1));
    when(policy.fieldPermissions())
        .thenReturn(
            Map.of(
                RecordPermissionModel.key(source.physicalModelId())
                    + "#"
                    + RecordPermissionModel.field("customer"),
                "VISIBLE"));
    var facade =
        new ApplicationFormInverseRecordServiceImpl(
            resolver,
            new FormInverseRecordQueries(records),
            new FormInverseRecordCommands(records),
            forms,
            mock(ApplicationFormRecordSchemaServiceImpl.class),
            runtime);
    var result =
        facade.describe(
            "customers",
            "customer-page",
            new org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity(
                RUNTIME, "transactions", RECORD));
    assertThat(result.source().document()).isSameAs(document);
    assertThat(result.fieldId()).isEqualTo("customer");
    assertThat(result.source().capabilities())
        .isEqualTo(
            new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities(
                false, true, true, true));
    assertThat(result.canUnlink()).isFalse();
  }

  @Test
  void inverseSchemaIntersectsSourceCapabilitiesAndAlwaysRechecksTheSavedParent() {
    var schemas = mock(ApplicationFormRecordSchemaServiceImpl.class);
    var commands = mock(FormInverseRecordCommands.class);
    var facade =
        new ApplicationFormInverseRecordServiceImpl(
            resolver,
            new FormInverseRecordQueries(records),
            commands,
            forms,
            schemas,
            mock(ApplicationFormRuntimeServiceImpl.class));
    var allowed =
        new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities(
            false, true, false, true);
    when(forms.capabilities(source)).thenReturn(allowed);
    var identity =
        new org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity(
            RUNTIME, "transactions", RECORD);
    facade.schema("customers", "customer-page", identity);
    verify(schemas)
        .schema(
            source,
            new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeCapabilities(
                false, true, false, true),
            true);
    verify(records).get(parent, RECORD);
    clearInvocations(commands, schemas, records);
    doThrow(new ApplicationFormException(ApplicationFormErrorCode.RECORD_NOT_FOUND, "Unavailable"))
        .when(records)
        .get(parent, RECORD);
    assertThatThrownBy(() -> facade.create("customers", "customer-page", identity, null))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(commands, schemas);
  }

  @Test
  void inverseCreateFixesTheParentAndPreservesTheMappingConfirmation() {
    var resolved = resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD);
    var commands = new FormInverseRecordCommands(records);
    var runtime = new ApplicationFormRuntimeIdentity("trades", 1);
    var intent =
        new FormReferenceMappingIntent(
            "1",
            List.of(
                new FormReferenceMappingConfirmation(
                    "customer",
                    null,
                    null,
                    null,
                    new FormReferenceMappingObservation("a".repeat(64), RECORD, 1L),
                    List.of("address_default"))));
    commands.create(
        resolved,
        new CreateApplicationFormRecordCommand(
            runtime, "create", FormRecordState.DRAFT, Map.of("address", "Confirmed"), intent));
    verify(records)
        .createRelated(
            eq(source),
            eq(new CreateApplicationFormRecordCommand(
                runtime,
                "create",
                FormRecordState.DRAFT,
                Map.of("address", "Confirmed", "customer", RECORD),
                intent)),
            any());
  }

  @Test
  void relatedSubmissionsUseTheFrozenSourceAndCannotBypassTheViewEditCeiling() {
    var submissions = mock(ApplicationFormSubmissionService.class);
    var preparation = mock(FormSubmissionPreparationService.class);
    var scopedViews = mock(FormInverseRelationResolver.class);
    var service = new FormInverseSubmissionService(scopedViews, submissions, preparation);
    var identity =
        new org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity(
            RUNTIME, "transactions", RECORD);
    var resolved = resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD);
    when(scopedViews.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD))
        .thenReturn(resolved);
    var command =
        new org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand(
            new ApplicationFormRuntimeIdentity("trades", 1),
            "submit",
            RECORD,
            0L,
            "process",
            2,
            null,
            "审批",
            Map.of(),
            Map.of(),
            List.of());
    service.submit("customers", "customer-page", identity, command);
    service.prepare("customers", "customer-page", identity, "process", 2);
    var scope =
        org.mockito.ArgumentCaptor.forClass(
            org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.class);
    verify(submissions)
        .submitRelated(
            org.mockito.ArgumentMatchers.eq(source),
            scope.capture(),
            org.mockito.ArgumentMatchers.eq(command));
    assertThat(scope.getValue().targetRecordId()).isEqualTo(RECORD);
    assertThat(scope.getValue().targetModelId()).isEqualTo(parent.physicalModelId());
    assertThat(scope.getValue().mode())
        .isEqualTo(org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.Mode.MAINTAIN);
    verify(preparation).prepare(source, "process", 2);
    var denied =
        new FormInverseRelationView(
            view.viewId(),
            view.label(),
            view.source(),
            view.sourceRelationId(),
            true,
            false,
            true,
            true,
            null);
    when(scopedViews.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD))
        .thenReturn(
            new FormInverseRelationResolver.ResolvedView(
                parent,
                source,
                denied,
                owning,
                resolved.sourceField(),
                resolved.sourceModelId(),
                RECORD));
    clearInvocations(submissions, preparation);
    assertThatThrownBy(() -> service.submit("customers", "customer-page", identity, command))
        .isInstanceOf(ApplicationFormException.class);
    assertThatThrownBy(() -> service.prepare("customers", "customer-page", identity, "process", 2))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(submissions, preparation);
  }

  @Test
  void inverseCreateBindsParentWithoutMutatingBrowserValuesOrAcceptingAnotherParent() {
    var resolved = resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD);
    var commands = new FormInverseRecordCommands(records);
    var request =
        new org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand(
            new ApplicationFormRuntimeIdentity("trades", 1),
            "create",
            FormRecordState.DRAFT,
            Map.of());
    commands.create(resolved, request);
    var intent =
        org.mockito.ArgumentCaptor.forClass(
            org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand.class);
    var scope =
        org.mockito.ArgumentCaptor.forClass(
            org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope.class);
    verify(records).createRelated(eq(source), intent.capture(), scope.capture());
    assertThat(intent.getValue().values()).containsExactlyEntriesOf(Map.of("customer", RECORD));
    assertThat(request.values()).isEmpty();
    assertThat(scope.getValue().targetRecordId()).isEqualTo(RECORD);
    assertThat(scope.getValue().relationId()).isEqualTo("customer");
    clearInvocations(records);
    assertThatThrownBy(
            () ->
                commands.create(
                    resolved,
                    new org.simplepoint.plugin.app.form.api.model
                        .CreateApplicationFormRecordCommand(
                        request.runtime(),
                        "tampered",
                        request.state(),
                        Map.of("customer", "another"))))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));
    verifyNoInteractions(records);
  }

  @Test
  void configuredActionCeilingsAndUnlinkRowShapeAreEnforcedBeforeStandardCommands() {
    var resolved = resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD);
    var disabled =
        new FormInverseRelationView(
            view.viewId(),
            view.label(),
            view.source(),
            view.sourceRelationId(),
            false,
            false,
            false,
            false,
            null);
    var limited =
        new FormInverseRelationResolver.ResolvedView(
            parent,
            source,
            disabled,
            owning,
            resolved.sourceField(),
            resolved.sourceModelId(),
            RECORD);
    var commands = new FormInverseRecordCommands(records);
    var update =
        new org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand(
            new ApplicationFormRuntimeIdentity("trades", 1),
            0,
            "update",
            FormRecordState.DRAFT,
            Map.of());
    clearInvocations(records);
    for (Runnable action :
        List.<Runnable>of(
            () -> commands.create(limited, null),
            () -> commands.update(limited, RECORD, update),
            () -> commands.delete(limited, RECORD, 0, update.runtime(), "delete"),
            () -> commands.unlink(limited, RECORD, update, null))) {
      assertThatThrownBy(action::run)
          .isInstanceOfSatisfying(
              ApplicationFormException.class,
              e ->
                  assertThat(e.errorCode())
                      .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
    }
    assertThatThrownBy(() -> commands.unlink(resolved, RECORD, update, RECORD))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ApplicationFormErrorCode.RECORD_INVALID));
    verifyNoInteractions(records);
  }

  @Test
  void readsBothEntriesWhenFrozenTargetAccessIsNarrowerThanParentAccess() {
    var narrow = form("customers", "restricted-page", "restricted-resource", parent.document());
    when(relations.resolve(source, "customer"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(owning, narrow));
    var resolved =
        resolver.resolve(
            "customers", "customer-page", RUNTIME, "transactions", RECORD.toUpperCase());
    assertThat(resolved.parentRecordId()).isEqualTo(RECORD);
    assertThat(resolved.source()).isSameAs(source);
    verify(records).get(parent, RECORD);
    verify(records).get(narrow, RECORD);
    doThrow(new ApplicationFormException(ApplicationFormErrorCode.RECORD_NOT_FOUND, "Unavailable"))
        .when(records)
        .get(narrow, RECORD);
    assertThatThrownBy(
            () -> resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD))
        .isInstanceOf(ApplicationFormException.class);
  }

  @Test
  void stalePageUnsavedParentAndUndeclaredViewNeverReadSourceMetadata() {
    for (Runnable request :
        List.<Runnable>of(
            () ->
                resolver.resolve(
                    "customers",
                    "customer-page",
                    new ApplicationFormRuntimeIdentity("customers", 2),
                    "transactions",
                    RECORD),
            () ->
                resolver.resolve(
                    "customers", "customer-page", RUNTIME, "transactions", "draft-row"),
            () ->
                resolver.resolve("customers", "customer-page", RUNTIME, "injected-view", RECORD))) {
      assertThatThrownBy(request::run).isInstanceOf(ApplicationFormException.class);
    }
    verifyNoInteractions(relations, records, contexts);
  }

  @Test
  void hiddenOrMaskedForeignKeyCannotBeUsedAsAnInverseQuerySideChannel() {
    for (String access : List.of("HIDDEN", "MASKED")) {
      when(policy.fieldPermissions())
          .thenReturn(
              Map.of(
                  RecordPermissionModel.key("trades-model")
                      + "#"
                      + RecordPermissionModel.field("customer"),
                  access));
      assertThatThrownBy(
              () -> resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD))
          .isInstanceOfSatisfying(
              ApplicationFormException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED));
    }
    verifyNoInteractions(records);
  }

  @Test
  void retargetedOwningRelationshipCannotRedirectTheInverseView() {
    var wrong = form("other-customers", "other-page", "other-resource", parent.document());
    when(relations.resolve(source, "customer"))
        .thenReturn(new FormRelationTargetResolver.ResolvedRelation(owning, wrong));
    assertThatThrownBy(
            () -> resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD))
        .isInstanceOf(ApplicationFormException.class);
    verifyNoInteractions(records);
  }

  @Test
  void fixedParentFilterIntersectsUserFiltersAndRetargetedRecordsAreUnavailable() {
    var resolved = resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD);
    var user =
        new ApplicationFormRecordFilter(
            ApplicationFormRecordFilterOperator.NOT_EQUALS, "customer", List.of(RECORD), List.of());
    var query = new ApplicationFormRecordPageQuery(2, 200, List.of("amount"), user, List.of());
    var reads = new FormInverseRecordQueries(records);
    reads.page(resolved, query);
    verify(records)
        .pageRelated(
            source,
            query,
            resolved.relation().relationId(),
            resolved.parent().physicalModelId(),
            RECORD);
    clearInvocations(records);
    when(records.getDecorated(eq(source), any()))
        .thenReturn(
            new ApplicationFormRecordView(
                "trade",
                3,
                FormRecordState.DRAFT,
                Map.of("customer", "aaaaaaaa-1111-2222-3333-000000000002")));
    assertThatThrownBy(() -> reads.get(resolved, "trade"))
        .isInstanceOfSatisfying(
            ApplicationFormException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(ApplicationFormErrorCode.RECORD_NOT_FOUND));
  }

  @Test
  void ownedMembershipRequiresAnAuthorizedMatchingRowAndCannotBorrowAnotherCollection() {
    var root = resolver.resolve("customers", "customer-page", RUNTIME, "transactions", RECORD);
    var relation =
        new FormRelationDefinition(
            "1",
            root.relation().relationId(),
            root.relation().cardinality(),
            new FormRelationField("lines", "customer"),
            root.relation().target());
    var related =
        new FormInverseRelationResolver.ResolvedView(
            root.parent(),
            root.source(),
            root.view(),
            relation,
            root.sourceField(),
            "detail-model",
            root.parentRecordId());
    var match = Map.of("values", Map.of("customer", RECORD));
    var other = Map.of("values", Map.of("customer", "aaaaaaaa-1111-2222-3333-000000000002"));
    FormInverseRecordQueries.requireMember(
        related,
        new ApplicationFormRecordView(
            "trade", 0, FormRecordState.DRAFT, Map.of("lines", List.of(other, match))));
    for (var values :
        List.<Map<String, Object>>of(
            Map.of(),
            Map.of("lines", List.of(other)),
            Map.of("otherLines", List.of(match)),
            Map.of("customer", RECORD))) {
      assertThatThrownBy(
              () ->
                  FormInverseRecordQueries.requireMember(
                      related,
                      new ApplicationFormRecordView("trade", 0, FormRecordState.DRAFT, values)))
          .isInstanceOfSatisfying(
              ApplicationFormException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(ApplicationFormErrorCode.RECORD_NOT_FOUND));
    }
  }

  private static PublishedApplicationForm form(
      String id, String page, String resource, ApplicationFormDocument document) {
    return new PublishedApplicationForm(
        "tenant-a", id, page, id, id, id, 1, resource, id + "-model", 1, "a".repeat(64), document);
  }
}
