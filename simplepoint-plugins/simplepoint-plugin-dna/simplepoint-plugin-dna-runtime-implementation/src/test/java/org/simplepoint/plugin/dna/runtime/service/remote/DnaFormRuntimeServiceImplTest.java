package org.simplepoint.plugin.dna.runtime.service.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelAccessService;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordCommandService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordInspectionService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordQueryService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordTransactionService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordWritePolicyService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPlanService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;

class DnaFormRuntimeServiceImplTest {

  @AfterEach
  void clearContexts() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @Test
  void restoresTheTrustedRecordPolicyOnlyForTheDelegateCall() {
    RecordQueryService queries = mock(RecordQueryService.class);
    final DnaFormRuntimeServiceImpl service = service(queries);
    AuthorizationContext previous = new AuthorizationContext();
    previous.setUserId("previous-user");
    previous.setAttributes(Map.of("X-Tenant-Id", "previous-tenant"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
    ResourcePolicyContext.set("previous-resource");
    when(queries.page(any()))
        .thenAnswer(
            invocation -> {
              AuthorizationContext current = AuthorizationContextHolder.getContext();
              assertThat(current.getUserId()).isEqualTo("user-a");
              assertThat(current.getAttribute("X-Tenant-Id")).isEqualTo("tenant-a");
              assertThat(current.getAttribute("X-Org-Dept-Id")).isEqualTo("dept-a");
              assertThat(current.activeDataScope().type()).isEqualTo("SELF");
              assertThat(current.activeFieldPermissions())
                  .containsEntry("dna.runtime.record.model-1#title", "EDITABLE");
              assertThat(ResourcePolicyContext.get()).isEqualTo("app.forms.orders");
              return new RecordPageView(0, 20, 0, List.of());
            });

    RecordPageView result =
        service.pageRecords(
            context(),
            new RecordPageQuery(
                "model-1",
                new RecordSchemaReference(
                    1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                0,
                20,
                List.of("title"),
                null,
                List.of()));

    assertThat(result.total()).isZero();
    assertThat(AuthorizationContextHolder.getContext()).isSameAs(previous);
    assertThat(ResourcePolicyContext.get()).isEqualTo("previous-resource");
  }

  @Test
  void typedPagesRequireTheirExplicitOperationAndRetainCallerPolicy() {
    final var queries = mock(RecordQueryService.class);
    var service = service(queries);
    var condition =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordValueCondition(
            org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator.IS_EMPTY,
            "title",
            List.of(),
            List.of());
    var query =
        new RecordPageQuery(
            "model-1",
            new RecordSchemaReference(1, "a".repeat(64)),
            0,
            50,
            List.of("title"),
            null,
            List.of(),
            condition);
    assertThatThrownBy(() -> service.pageRecords(context(), query))
        .isInstanceOfSatisfying(
            org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(
                        org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode
                            .RECORD_QUERY_INVALID));
    verifyNoInteractions(queries);
    when(queries.page(query))
        .thenAnswer(
            call -> {
              assertThat(AuthorizationContextHolder.getContext().getUserId()).isEqualTo("user-a");
              assertThat(AuthorizationContextHolder.getContext().activeDataScope().type())
                  .isEqualTo("SELF");
              assertThat(((RecordPageQuery) call.getArgument(0)).condition()).isSameAs(condition);
              return new RecordPageView(0, 50, 0, List.of());
            });
    assertThat(service.pageConditionedRecords(context(), query).total()).isZero();
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    assertThat(ResourcePolicyContext.get()).isNull();
  }

  @Test
  void relatedPoliciesSurviveProviderInvocationWithoutReplacingTheSourceAndAreRestoredOnFailure() {
    final var queries = mock(RecordQueryService.class);
    var original = new AuthorizationContext();
    original.setUserId("original");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, original);
    ResourcePolicyContext.set("original-resource");
    var source = context();
    var additional =
        new org.simplepoint.plugin.dna.runtime.api.context.DnaResourcePolicy(
            "1",
            "CUSTOM",
            Set.of("sales"),
            false,
            List.of(),
            Map.of("dna.runtime.record.customer#name", "MASKED"));
    var invocation =
        new DnaInvocationContext(
            source.tenantId(),
            source.actorId(),
            source.organizationId(),
            source.resourceCode(),
            false,
            Set.of(source.resourceCode(), "customers"),
            source.dataScopeType(),
            source.departmentIds(),
            source.dataScopeIncludeSelf(),
            source.dataScopeExpressions(),
            source.fieldPermissions(),
            Map.of("customers", additional));
    when(queries.page(any()))
        .thenAnswer(
            call -> {
              var policy = AuthorizationContextHolder.getContext();
              assertThat(policy.isResourcePolicyDefaultsResolved()).isFalse();
              assertThat(policy.activeDataScope().type()).isEqualTo("SELF");
              assertThat(policy.dataScopeFor("customers").type()).isEqualTo("CUSTOM");
              assertThat(policy.dataScopeFor("customers").departmentIds()).containsExactly("sales");
              assertThat(policy.fieldPermissionsFor("customers"))
                  .containsEntry("dna.runtime.record.customer#name", "MASKED");
              assertThat(ResourcePolicyContext.get()).isEqualTo(source.resourceCode());
              throw new IllegalStateException("delegate failure");
            });
    assertThatThrownBy(
            () ->
                service(queries)
                    .pageRecords(
                        invocation,
                        new RecordPageQuery(
                            "model-1",
                            new RecordSchemaReference(1, "a".repeat(64)),
                            0,
                            50,
                            List.of(),
                            null,
                            List.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("delegate failure");
    assertThat(AuthorizationContextHolder.getContext()).isSameAs(original);
    assertThat(ResourcePolicyContext.get()).isEqualTo("original-resource");
  }

  @Test
  void resourceProjectionRequiresTheCallerGuardAndRestoresManagementContextOnFailure() {
    PhysicalModelAccessService access = mock(PhysicalModelAccessService.class);
    DnaFormRuntimeCallerGuard guard = mock(DnaFormRuntimeCallerGuard.class);
    DnaFormRuntimeServiceImpl service =
        new DnaFormRuntimeServiceImpl(
            mock(PhysicalModelService.class),
            mock(SchemaPlanService.class),
            mock(SchemaPublicationService.class),
            access,
            mock(RecordQueryService.class),
            mock(RecordCommandService.class),
            mock(RecordTransactionService.class),
            mock(RecordInspectionService.class),
            mock(RecordWritePolicyService.class),
            guard);
    var command =
        new PhysicalModelResourceProjectionCommand("model-1", "app.forms.orders", "app:page-1", 3);
    when(access.project(command))
        .thenAnswer(
            invocation -> {
              assertThat(AuthorizationContextHolder.getContext().getIsAdministrator()).isTrue();
              assertThat(AuthorizationContextHolder.getContext().getAttribute("X-Tenant-Id"))
                  .isEqualTo("tenant-a");
              throw new IllegalStateException("Projection unavailable");
            });

    assertThatThrownBy(() -> service.projectModelResource(context(), command))
        .isInstanceOf(IllegalStateException.class);
    verify(guard).requireTrustedCaller();
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    assertThat(ResourcePolicyContext.get()).isNull();
  }

  @Test
  void grantsServiceAuthorityOnlyToTrustedManagementDelegates() {
    PhysicalModelService models = mock(PhysicalModelService.class);
    DnaFormRuntimeServiceImpl service =
        new DnaFormRuntimeServiceImpl(
            models,
            mock(SchemaPlanService.class),
            mock(SchemaPublicationService.class),
            mock(PhysicalModelAccessService.class),
            mock(RecordQueryService.class),
            mock(RecordCommandService.class),
            mock(RecordTransactionService.class),
            mock(RecordInspectionService.class),
            mock(RecordWritePolicyService.class),
            mock(DnaFormRuntimeCallerGuard.class));
    when(models.get("model-1"))
        .thenAnswer(
            invocation -> {
              assertThat(AuthorizationContextHolder.getContext().getIsAdministrator()).isTrue();
              assertThat(AuthorizationContextHolder.getContext().getUserId()).isEqualTo("user-a");
              return null;
            });

    service.getModel(context(), "model-1");

    assertThat(AuthorizationContextHolder.getContext()).isNull();
  }

  @Test
  void receiptLookupUsesRecordPolicyAndRestoresContextWhenTheDelegateFails() {
    RecordCommandService commands = mock(RecordCommandService.class);
    DnaFormRuntimeServiceImpl service =
        new DnaFormRuntimeServiceImpl(
            mock(PhysicalModelService.class),
            mock(SchemaPlanService.class),
            mock(SchemaPublicationService.class),
            mock(PhysicalModelAccessService.class),
            mock(RecordQueryService.class),
            commands,
            mock(RecordTransactionService.class),
            mock(RecordInspectionService.class),
            mock(RecordWritePolicyService.class),
            mock(DnaFormRuntimeCallerGuard.class));
    org.mockito.stubbing.Answer<Object> unavailable =
        invocation -> {
          var actor = AuthorizationContextHolder.getContext();
          assertThat(actor.getUserId()).isEqualTo("user-a");
          assertThat(actor.getIsAdministrator()).isFalse();
          assertThat(actor.activeDataScope().type()).isEqualTo("SELF");
          assertThat(ResourcePolicyContext.get()).isEqualTo("app.forms.orders");
          throw new IllegalStateException("Probe unavailable");
        };
    when(commands.findUpdateResult(any())).thenAnswer(unavailable);
    when(commands.findCreateResult(any())).thenAnswer(unavailable);

    assertThatThrownBy(
            () ->
                service.findRecordUpdateResult(
                    context(),
                    new RecordUpdateCommand(
                        "model-1",
                        new RecordSchemaReference(1, "a".repeat(64)),
                        "record-1",
                        0,
                        Map.of("title", "Original"),
                        "update-1")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    assertThat(ResourcePolicyContext.get()).isNull();
    assertThatThrownBy(
            () ->
                service.findRecordCreateResult(
                    context(),
                    new RecordCreateCommand(
                        "model-1",
                        new RecordSchemaReference(1, "a".repeat(64)),
                        "create-1",
                        Map.of("title", "Original"))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    assertThat(ResourcePolicyContext.get()).isNull();
  }

  @Test
  void completenessRequiresGuardedManagementAndRestoresThePreviousContext() {
    RecordInspectionService inspections = mock(RecordInspectionService.class);
    DnaFormRuntimeCallerGuard guard = mock(DnaFormRuntimeCallerGuard.class);
    final var service =
        new DnaFormRuntimeServiceImpl(
            mock(PhysicalModelService.class),
            mock(SchemaPlanService.class),
            mock(SchemaPublicationService.class),
            mock(PhysicalModelAccessService.class),
            mock(RecordQueryService.class),
            mock(RecordCommandService.class),
            mock(RecordTransactionService.class),
            inspections,
            mock(RecordWritePolicyService.class),
            guard);
    final var query =
        new RecordCompletenessQuery(
            "model-1",
            "app-1",
            "form-1",
            new RecordSchemaReference(1, "a".repeat(64)),
            List.of("title"),
            null);
    AuthorizationContext previous = new AuthorizationContext();
    previous.setIsAdministrator(false);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
    ResourcePolicyContext.set("previous");
    when(inspections.inspectCompleteness(query))
        .thenAnswer(
            invocation -> {
              var current = AuthorizationContextHolder.getContext();
              assertThat(current.getIsAdministrator()).isTrue();
              assertThat(current.getAttribute("X-Tenant-Id")).isEqualTo("tenant-a");
              assertThat(current.getUserId()).isEqualTo("user-a");
              throw new IllegalStateException("Inspection unavailable");
            });
    assertThatThrownBy(() -> service.inspectRecordCompleteness(context(), query))
        .isInstanceOf(IllegalStateException.class);
    verify(guard).requireTrustedCaller();
    assertThat(AuthorizationContextHolder.getContext()).isSameAs(previous);
    assertThat(ResourcePolicyContext.get()).isEqualTo("previous");
  }

  @Test
  void untrustedCompletenessCallerNeverReachesInspection() {
    RecordInspectionService inspections = mock(RecordInspectionService.class);
    DnaFormRuntimeCallerGuard guard = mock(DnaFormRuntimeCallerGuard.class);
    doThrow(new SecurityException("Untrusted service")).when(guard).requireTrustedCaller();
    var service =
        new DnaFormRuntimeServiceImpl(
            mock(PhysicalModelService.class),
            mock(SchemaPlanService.class),
            mock(SchemaPublicationService.class),
            mock(PhysicalModelAccessService.class),
            mock(RecordQueryService.class),
            mock(RecordCommandService.class),
            mock(RecordTransactionService.class),
            inspections,
            mock(RecordWritePolicyService.class),
            guard);
    assertThatThrownBy(() -> service.inspectRecordCompleteness(context(), null))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(inspections);
    assertThat(AuthorizationContextHolder.getContext()).isNull();
  }

  @Test
  void policyCancellationRequiresTrustedManagementAndRestoresContext() {
    var policies = mock(RecordWritePolicyService.class);
    var guard = mock(DnaFormRuntimeCallerGuard.class);
    var service =
        new DnaFormRuntimeServiceImpl(
            mock(PhysicalModelService.class),
            mock(SchemaPlanService.class),
            mock(SchemaPublicationService.class),
            mock(PhysicalModelAccessService.class),
            mock(RecordQueryService.class),
            mock(RecordCommandService.class),
            mock(RecordTransactionService.class),
            mock(RecordInspectionService.class),
            policies,
            guard);
    var command =
        new RecordWritePolicyPublicationCommand(
            "model-1",
            "app-1",
            "form-1",
            new RecordSchemaReference(1, "a".repeat(64)),
            0,
            1,
            "publish",
            "b".repeat(64),
            Map.of(),
            1000);
    when(policies.cancel(command))
        .thenAnswer(
            invocation -> {
              assertThat(AuthorizationContextHolder.getContext().getIsAdministrator()).isTrue();
              assertThat(AuthorizationContextHolder.getContext().getAttribute("X-Tenant-Id"))
                  .isEqualTo("tenant-a");
              throw new IllegalStateException("Outcome unknown");
            });
    assertThatThrownBy(() -> service.cancelRecordWritePolicy(context(), command))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    assertThat(ResourcePolicyContext.get()).isNull();
    doThrow(new SecurityException("Untrusted service")).when(guard).requireTrustedCaller();
    assertThatThrownBy(() -> service.cancelRecordWritePolicy(context(), command))
        .isInstanceOf(SecurityException.class);
    verify(policies).cancel(command);
  }

  @Test
  void policyPublicationRequiresTrustedManagementAndAlwaysRestoresContext() {
    var policies = mock(RecordWritePolicyService.class);
    var guard = mock(DnaFormRuntimeCallerGuard.class);
    var service =
        new DnaFormRuntimeServiceImpl(
            mock(PhysicalModelService.class),
            mock(SchemaPlanService.class),
            mock(SchemaPublicationService.class),
            mock(PhysicalModelAccessService.class),
            mock(RecordQueryService.class),
            mock(RecordCommandService.class),
            mock(RecordTransactionService.class),
            mock(RecordInspectionService.class),
            policies,
            guard);
    var command =
        new RecordWritePolicyPublicationCommand(
            "model-1",
            "app-1",
            "form-1",
            new RecordSchemaReference(1, "a".repeat(64)),
            0,
            1,
            "publish",
            "b".repeat(64),
            Map.of(),
            1000);
    when(policies.publish(command))
        .thenAnswer(
            invocation -> {
              assertThat(AuthorizationContextHolder.getContext().getIsAdministrator()).isTrue();
              assertThat(AuthorizationContextHolder.getContext().getAttribute("X-Tenant-Id"))
                  .isEqualTo("tenant-a");
              throw new IllegalStateException("Unavailable");
            });
    assertThatThrownBy(() -> service.publishRecordWritePolicy(context(), command))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuthorizationContextHolder.getContext()).isNull();
    assertThat(ResourcePolicyContext.get()).isNull();
    doThrow(new SecurityException("Untrusted service")).when(guard).requireTrustedCaller();
    assertThatThrownBy(() -> service.publishRecordWritePolicy(context(), command))
        .isInstanceOf(SecurityException.class);
    verify(policies).publish(command); // Only the first, trusted call reached the delegate.
  }

  @Test
  void referenceVisibilityUsesItsExplicitAuthenticatedOperation() {
    final var queries = mock(RecordQueryService.class);
    var service = service(queries);
    var id = java.util.UUID.randomUUID().toString();
    var query =
        new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceVisibilityQuery(
            "model-1", new RecordSchemaReference(1, "a".repeat(64)), "title", List.of(id), 1000);
    when(queries.visibleReferences(query))
        .thenAnswer(
            call -> {
              assertThat(ResourcePolicyContext.get()).isEqualTo(context().resourceCode());
              assertThat(AuthorizationContextHolder.getContext().getUserId())
                  .isEqualTo(context().actorId());
              return List.of(id);
            });
    ResourcePolicyContext.set("previous-resource");
    assertThat(service.visibleReferenceRecords(context(), query)).containsExactly(id);
    assertThat(ResourcePolicyContext.get()).isEqualTo("previous-resource");
  }

  private static DnaInvocationContext context() {
    return new DnaInvocationContext(
        "tenant-a",
        "user-a",
        "dept-a",
        "app.forms.orders",
        false,
        Set.of("app.forms.orders"),
        "SELF",
        Set.of(),
        false,
        List.of(),
        Map.of("dna.runtime.record.model-1#title", "EDITABLE"));
  }

  private static DnaFormRuntimeServiceImpl service(final RecordQueryService queries) {
    return new DnaFormRuntimeServiceImpl(
        mock(PhysicalModelService.class),
        mock(SchemaPlanService.class),
        mock(SchemaPublicationService.class),
        mock(PhysicalModelAccessService.class),
        queries,
        mock(RecordCommandService.class),
        mock(RecordTransactionService.class),
        mock(RecordInspectionService.class),
        mock(RecordWritePolicyService.class),
        mock(DnaFormRuntimeCallerGuard.class));
  }
}
