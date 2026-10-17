package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStep;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationView;
import org.simplepoint.plugin.app.form.api.model.PrepareApplicationFormDataCommand;
import org.simplepoint.plugin.app.form.api.model.PublishApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.ApplicationFormPublicationStateService.PublicationClaim;
import org.simplepoint.plugin.app.form.service.ApplicationFormPublicationStateService.PublicationWork;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.app.form.service.FormPageResourceCoordinator.PreparedPageResource;
import org.simplepoint.plugin.app.form.service.FormWritePolicyPublicationStateService.PreparedPolicy;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStatus;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

class ApplicationFormPublicationServiceImplTest {

  private ApplicationFormPublicationStateService state;
  private FormDnaSchemaPublicationCoordinator dna;
  private DnaInvocationContextFactory contexts;
  private ApplicationFormPageBindingStateService pageBindings;
  private FormPageResourceCoordinator pageResources;
  private FormDnaResourceBindingCoordinator dnaResources;
  private ApplicationAccessPort applicationAccess;
  private FormRequestContext requestContext;
  private FormWritePolicyPublicationCoordinator policies;
  private FormPublicationImpactService impacts;
  private FormPublicationInspectionService inspections;
  private ApplicationFormPublicationServiceImpl service;
  private final FormDetailSchemaCoordinator details = mock(FormDetailSchemaCoordinator.class);

  @BeforeEach
  void setUp() {
    state = mock(ApplicationFormPublicationStateService.class);
    policies = mock(FormWritePolicyPublicationCoordinator.class);
    impacts = mock(FormPublicationImpactService.class);
    inspections = mock(FormPublicationInspectionService.class);
    when(impacts.requirements(any())).thenReturn(new FormWritePolicyImpact(0, 1, Set.of(), Map.of()));
    when(policies.prepareTarget(any(), any())).thenReturn(new PreparedPolicy("policy", FormWritePolicyPublicationStatus.TARGET_READY,
        null, null, null));
    dna = mock(FormDnaSchemaPublicationCoordinator.class);
    contexts = mock(DnaInvocationContextFactory.class);
    pageBindings = mock(ApplicationFormPageBindingStateService.class);
    pageResources = mock(FormPageResourceCoordinator.class);
    dnaResources = mock(FormDnaResourceBindingCoordinator.class);
    applicationAccess = mock(ApplicationAccessPort.class);
    requestContext = mock(FormRequestContext.class);
    when(requestContext.requireActor()).thenReturn(
        new FormRequestContext.FormActor("tenant-a", "user-a")
    );
    when(pageBindings.reserve(any(), any(), any()))
        .thenReturn(bindingView(ApplicationFormPageBindingStatus.DATA_PREPARING));
    service = new ApplicationFormPublicationServiceImpl(
        state,
        dna,
        contexts,
        pageBindings,
        pageResources,
        dnaResources,
        applicationAccess,
        requestContext,
        policies,
        impacts,
        inspections,
        details
    );
  }

  @Test
  void blockedPublicationKeepsTheOriginalInspectionAndDoesNotProjectResources() {
    var work = bindingWork();
    when(state.claim(any(), any(), any())).thenReturn(new PublicationClaim(false, null, view(ApplicationFormPublicationStatus.DATA_READY)));
    when(pageBindings.claim(any(), any(), any())).thenReturn(new ApplicationFormPageBindingStateService.BindingClaim(
        true, work, bindingView(ApplicationFormPageBindingStatus.RESOURCE_PREPARING)));
    when(policies.prepareTarget(any(), any())).thenReturn(new PreparedPolicy("policy", FormWritePolicyPublicationStatus.PREPARED,
        null, null, null));
    when(pageBindings.get("app-a", "form-a", "binding-a")).thenReturn(bindingView(ApplicationFormPageBindingStatus.FAILED));
    var report = new org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection(Instant.now(), List.of(
        new org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection.Issue("required",
            org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection.Kind.REQUIRED, List.of("Name"), "3")), "page-old");
    when(inspections.get("app-a", "form-a", "binding-a")).thenReturn(report);
    var result = service.publish("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "publish-a"));
    assertThat(result.inspection()).isSameAs(report);
    verify(pageBindings).fail("binding-a", "binding-token", ApplicationFormErrorCode.PUBLICATION_DATA_CONFLICT);
    verifyNoInteractions(dnaResources);
    verify(pageResources, never()).activate(any());
    verify(pageBindings, never()).complete(any(), any(), any());
  }

  @Test
  void legacyResourceAttemptDoesNotAcquireNewCancellationSemantics() {
    var work = new ApplicationFormPageBindingStateService.BindingWork("binding-a", "binding-token", "tenant-a", "app-a", "page-a", "form-a", 1,
        "model-a", "page:page-a", "Orders", null, 0, 1, 0);
    when(state.claim(any(), any(), any())).thenReturn(new PublicationClaim(false, null, view(ApplicationFormPublicationStatus.DATA_READY)));
    when(pageBindings.claim(any(), any(), any())).thenReturn(new ApplicationFormPageBindingStateService.BindingClaim(
        true, work, bindingView(ApplicationFormPageBindingStatus.RESOURCE_PREPARING)));
    when(pageBindings.complete(any(), any(), any())).thenReturn(bindingView(ApplicationFormPageBindingStatus.ACTIVE));
    service.publish("app-a", "form-a", new PublishApplicationFormCommand(1, "page-a", "publish-a"));
    verifyNoInteractions(impacts, policies);
    verify(dnaResources).bind(work, null);
  }

  @Test
  void resumesCancellationBeforeProjectingResourcesOrActivatingThePage() {
    var command = new PublishApplicationFormCommand(1, "page-a", "publish-a");
    var work = bindingWork();
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(false, null, view(ApplicationFormPublicationStatus.DATA_READY)));
    when(pageBindings.claimPolicyRecovery("app-a", "form-a", "binding-a")).thenReturn(
        new ApplicationFormPageBindingStateService.BindingClaim(true, work, bindingView(ApplicationFormPageBindingStatus.ACTIVATING)));
    when(policies.resumeTarget(work)).thenReturn(new PreparedPolicy("policy", FormWritePolicyPublicationStatus.CANCELLED, null, null, null));
    when(pageBindings.get("app-a", "form-a", "binding-a")).thenReturn(bindingView(ApplicationFormPageBindingStatus.CANCELLED));

    assertThat(service.publish("app-a", "form-a", command).pageBinding().status()).isEqualTo(ApplicationFormPageBindingStatus.CANCELLED);
    verifyNoInteractions(dna, dnaResources, pageResources);
    verify(state, never()).claim(any(), any(), any());
    verify(pageBindings, never()).complete(any(), any(), any());
  }

  @Test
  void cancelsUsingSavedIdentityAndDoesNotCreateResourcesAfterConfirmation() {
    var work = bindingWork();
    when(pageBindings.claimCancellation("app-a", "form-a", "binding-a")).thenReturn(
        new ApplicationFormPageBindingStateService.BindingClaim(true, work, bindingView(ApplicationFormPageBindingStatus.ACTIVATING)));
    when(policies.cancelTarget(work)).thenReturn(new PreparedPolicy("policy", FormWritePolicyPublicationStatus.CANCELLED, null, null, null));
    when(pageBindings.get("app-a", "form-a", "binding-a")).thenReturn(bindingView(ApplicationFormPageBindingStatus.CANCELLED));
    assertThat(service.cancel("app-a", "form-a", "binding-a").pageBinding().status()).isEqualTo(ApplicationFormPageBindingStatus.CANCELLED);
    verifyNoInteractions(dna, dnaResources, pageResources);
    verify(state, never()).claim(any(), any(), any());
    var failure = new ApplicationFormException(ApplicationFormErrorCode.PUBLICATION_FAILED, "Response lost");
    when(policies.cancelTarget(work)).thenThrow(failure);
    assertThatThrownBy(() -> service.cancel("app-a", "form-a", "binding-a")).isSameAs(failure);
    verify(pageBindings).fail("binding-a", "binding-token", ApplicationFormErrorCode.PUBLICATION_FAILED);
  }

  @Test
  void cancellationCompletesTheOriginalReleaseWhenTargetPublicationWon() {
    var work = bindingWork();
    var resource = new PreparedPageResource("tenant-a", "page:page-a", "resource-a", "code-a", "/runtime", true);
    when(pageBindings.claimCancellation("app-a", "form-a", "binding-a")).thenReturn(
        new ApplicationFormPageBindingStateService.BindingClaim(true, work, bindingView(ApplicationFormPageBindingStatus.ACTIVATING)));
    when(state.getForVersion("app-a", "form-a", 1)).thenReturn(view(ApplicationFormPublicationStatus.DATA_READY));
    when(policies.cancelTarget(work)).thenReturn(new PreparedPolicy("policy", FormWritePolicyPublicationStatus.TARGET_READY, null, null, null));
    when(pageResources.prepare(work)).thenReturn(resource);
    when(pageResources.activate(work)).thenReturn(resource);
    when(pageBindings.complete("binding-a", "binding-token", resource)).thenReturn(bindingView(ApplicationFormPageBindingStatus.ACTIVE));
    when(state.get("app-a", "form-a", "publication-a")).thenReturn(view(ApplicationFormPublicationStatus.ACTIVE));
    assertThat(service.cancel("app-a", "form-a", "binding-a").pageBinding().status()).isEqualTo(ApplicationFormPageBindingStatus.ACTIVE);
    var order = inOrder(policies, pageResources, dnaResources, pageBindings);
    order.verify(policies).cancelTarget(work);
    order.verify(pageResources).prepare(work);
    order.verify(dnaResources).bind(work, resource);
    order.verify(pageResources).activate(work);
    order.verify(pageBindings).complete("binding-a", "binding-token", resource);
    verifyNoInteractions(dna);
  }

  private static ApplicationFormPageBindingStateService.BindingWork bindingWork() {
    return new ApplicationFormPageBindingStateService.BindingWork("binding-a", "binding-token", "tenant-a", "app-a", "page-a", "form-a", 1,
        "model-a", "page:page-a", "Orders", null, 0, 1);
  }

  @Test
  void activeReplayRepairsOnlyTheDurableResourceProjection() {
    var command = new PublishApplicationFormCommand(1, "page-a", "publish-a");
    var active = bindingView(ApplicationFormPageBindingStatus.ACTIVE);
    var work = new ApplicationFormPageBindingStateService.BindingWork(
        "binding-a", null, "tenant-a", "app-a", "page-a", "form-a", 1,
        "model-a", "page:page-a", "Orders", null, 0, 4);
    var resource = new PreparedPageResource(
        "tenant-a", "page:page-a", "resource-a", "app.runtime.page.a", "/runtime", true);
    when(pageBindings.reserve("app-a", "form-a", command)).thenReturn(active);
    when(pageBindings.activeProjection("app-a", "form-a", "binding-a"))
        .thenReturn(new ApplicationFormPageBindingStateService.ActiveResourceProjection(work, resource));

    assertThat(service.publish("app-a", "form-a", command).pageBinding()).isSameAs(active);

    verify(dnaResources).bind(work, resource);
    verify(state, never()).claim(any(), any(), any());
    verify(pageBindings, never()).claim(any(), any(), any());
    verifyNoInteractions(dna);
    verify(pageResources).refreshPermissions(work);
    verify(pageResources, never()).activate(any());
    verify(pageResources, never()).prepare(any());
    // A later projection must not be overwritten or the current APP publication reopened.
    var superseded = new ApplicationFormException(ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED, "superseded");
    when(dnaResources.bind(work, resource)).thenThrow(superseded);
    assertThatThrownBy(() -> service.publish("app-a", "form-a", command)).isSameAs(superseded);
    verify(pageBindings, never()).fail(any(), any(), any());
  }

  @Test
  void retiredIntentReplayDoesNotPrepareDataOrReactivateResources() {
    var command = new PublishApplicationFormCommand(1, "page-a", "publish-a");
    var retired = bindingView(ApplicationFormPageBindingStatus.RETIRED);
    when(pageBindings.reserve("app-a", "form-a", command)).thenReturn(retired);

    var result = service.publish("app-a", "form-a", command);

    assertThat(result.pageBinding()).isSameAs(retired);
    assertThat(result.dataPublication()).isNull();
    verify(state, never()).claim(any(), any(), any());
    verifyNoInteractions(dna, pageResources, dnaResources);
  }

  @Test
  void resumesOnlyTheDurableCommandAndRecordsPreparationFailure() {
    var stored = new PublishApplicationFormCommand(1, "page-a", "publish-a");
    when(pageBindings.resumeCommand("app-a", "form-a", "binding-a")).thenReturn(stored);
    var failure = new ApplicationFormException(ApplicationFormErrorCode.PUBLICATION_FAILED, "unavailable");
    when(state.claim("app-a", "form-a", command())).thenThrow(failure);

    assertThatThrownBy(() -> service.resume("app-a", "form-a", "binding-a")).isSameAs(failure);

    verify(pageBindings).reserve("app-a", "form-a", stored);
    verify(pageBindings).preparationFailed("binding-a", ApplicationFormErrorCode.PUBLICATION_FAILED);
    verifyNoInteractions(dna, pageResources, dnaResources);
  }

  @Test
  void returnsCurrentStateWhenAnotherAttemptOwnsTheLease() {
    ApplicationFormPublicationView current = view(
        ApplicationFormPublicationStatus.DATA_PREPARING
    );
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(false, null, current)
    );

    assertThat(service.prepareData("app-a", "form-a", command())).isSameAs(current);

    verify(dna, never()).prepare(any(), any());
  }

  @Test
  void completesDataOnlyAfterDnaReportsAnActiveSchema() {
    PublicationWork work = work();
    DnaInvocationContext context = context();
    PreparedDnaSchema active = prepared(SchemaMigrationStatus.ACTIVE);
    ApplicationFormPublicationView complete = view(
        ApplicationFormPublicationStatus.DATA_READY
    );
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(true, work, view(ApplicationFormPublicationStatus.DATA_PREPARING))
    );
    when(contexts.current()).thenReturn(context);
    when(dna.prepare(any(), any())).thenReturn(active);
    when(state.completeData("publication-a", "token-a", active)).thenReturn(complete);

    assertThat(service.prepareData("app-a", "form-a", command())).isSameAs(complete);

    InOrder order = inOrder(state, dna);
    order.verify(state).claim("app-a", "form-a", command());
    order.verify(dna).prepare(any(), any());
    order.verify(state).completeData("publication-a", "token-a", active);
  }

  @Test
  void waitsForEveryChildBeforeCompletingDataAndPersistsBindings() {
    var base = work();
    var document = new org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of(),
        List.of(new org.simplepoint.plugin.app.form.api.model.FormDetailDefinition("lines", "lines", "Lines", List.of(), List.of())));
    var work = new PublicationWork(base.publicationId(), base.executionToken(), base.applicationId(), base.formId(), base.displayName(),
        base.physicalModelId(), base.formVersion(), base.recoverStaleDna(), base.schema(), base.storageChecksum(), document);
    var current = view(ApplicationFormPublicationStatus.DATA_PREPARING);
    var active = prepared(SchemaMigrationStatus.ACTIVE);
    when(state.claim("app-a", "form-a", command())).thenReturn(new PublicationClaim(true, work, current));
    when(contexts.current()).thenReturn(context());
    when(dna.prepare(any(), any())).thenReturn(active);
    when(state.recordDataProgress("publication-a", "token-a", active)).thenReturn(current);
    assertThat(service.prepareData("app-a", "form-a", command())).isSameAs(current);
    verify(state, never()).completeData(any(), any(), any());
    when(details.prepare(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn("bindings");
    service.prepareData("app-a", "form-a", command());
    verify(state).completeData("publication-a", "token-a", new PreparedDnaSchema("model-a", 1, "migration-a", SchemaMigrationStatus.ACTIVE, "bindings"));
  }

  @Test
  void persistsNonterminalDnaProgressForLaterRecovery() {
    PublicationWork work = work();
    PreparedDnaSchema migrating = prepared(SchemaMigrationStatus.MIGRATING);
    ApplicationFormPublicationView current = view(
        ApplicationFormPublicationStatus.DATA_PREPARING
    );
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(true, work, current)
    );
    when(contexts.current()).thenReturn(context());
    when(dna.prepare(any(), any())).thenReturn(migrating);
    when(state.recordDataProgress("publication-a", "token-a", migrating))
        .thenReturn(current);

    assertThat(service.prepareData("app-a", "form-a", command())).isSameAs(current);

    verify(state).recordDataProgress("publication-a", "token-a", migrating);
    verify(state, never()).completeData(any(), any(), any());
  }

  @Test
  void recordsStableFailureBeforeReturningIt() {
    PublicationWork work = work();
    ApplicationFormException review = new ApplicationFormException(
        ApplicationFormErrorCode.PUBLICATION_REVIEW_REQUIRED,
        "review required"
    );
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(
            true,
            work,
            view(ApplicationFormPublicationStatus.DATA_PREPARING)
        )
    );
    when(contexts.current()).thenReturn(context());
    when(dna.prepare(any(), any())).thenThrow(review);

    assertThatThrownBy(() -> service.prepareData("app-a", "form-a", command()))
        .isSameAs(review);

    verify(state).fail(
        "publication-a",
        "token-a",
        ApplicationFormErrorCode.PUBLICATION_REVIEW_REQUIRED
    );
  }

  @Test
  void publishesDataThenPreparesAndActivatesThePageResource() {
    ApplicationFormPublicationView dataReady = view(ApplicationFormPublicationStatus.DATA_READY);
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(false, null, dataReady)
    );
    var command = new PublishApplicationFormCommand(1, "page-a", "publish-a");
    var work = new ApplicationFormPageBindingStateService.BindingWork(
        "binding-a",
        "binding-token",
        "tenant-a",
        "app-a",
        "page-a",
        "form-a",
        1,
        "model-a",
        "page:page-a",
        "Orders",
        null,
        0, 1
    );
    when(pageBindings.claim("app-a", "form-a", command)).thenReturn(
        new ApplicationFormPageBindingStateService.BindingClaim(
            true,
            work,
            bindingView(ApplicationFormPageBindingStatus.RESOURCE_PREPARING)
        )
    );
    PreparedPageResource prepared = new PreparedPageResource(
        "tenant-a", "page:page-a", "resource-a", "app.runtime.page.a", "/runtime", false
    );
    PreparedPageResource active = new PreparedPageResource(
        "tenant-a", "page:page-a", "resource-a", "app.runtime.page.a", "/runtime", true
    );
    when(pageResources.prepare(work)).thenReturn(prepared);
    when(pageResources.activate(work)).thenReturn(active);
    when(pageBindings.complete("binding-a", "binding-token", active)).thenReturn(
        bindingView(ApplicationFormPageBindingStatus.ACTIVE)
    );
    ApplicationFormPublicationView activated = view(ApplicationFormPublicationStatus.ACTIVE);
    when(state.get("app-a", "form-a", "publication-a")).thenReturn(activated);

    var result = service.publish("app-a", "form-a", command);

    assertThat(result.dataPublication()).isSameAs(activated);
    assertThat(result.pageBinding().status()).isEqualTo(ApplicationFormPageBindingStatus.ACTIVE);
    InOrder order = inOrder(pageResources, pageBindings, dnaResources, state);
    order.verify(pageBindings).reserve("app-a", "form-a", command);
    order.verify(state).claim("app-a", "form-a", command());
    order.verify(pageBindings).claim("app-a", "form-a", command);
    order.verify(pageResources).prepare(work);
    order.verify(pageBindings).resourceReady("binding-a", "binding-token", prepared);
    order.verify(dnaResources).bind(work, prepared);
    order.verify(pageResources).activate(work);
    order.verify(pageBindings).complete("binding-a", "binding-token", active);
    verify(state).get("app-a", "form-a", "publication-a");
  }

  @Test
  void leavesPageUntouchedWhileDnaMigrationIsStillRunning() {
    ApplicationFormPublicationView migrating = view(
        ApplicationFormPublicationStatus.DATA_PREPARING
    );
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(false, null, migrating)
    );

    var result = service.publish(
        "app-a",
        "form-a",
        new PublishApplicationFormCommand(1, "page-a", "publish-a")
    );

    assertThat(result.dataPublication()).isSameAs(migrating);
    assertThat(result.pageBinding().status()).isEqualTo(ApplicationFormPageBindingStatus.DATA_PREPARING);
    verify(pageBindings, never()).claim(any(), any(), any());
    verify(pageResources, never()).prepare(any());
    verify(dnaResources, never()).bind(any(), any());
    verify(state, times(1)).claim("app-a", "form-a", command());
  }

  @Test
  void activePublicationReplayReturnsTheRefreshedAggregateWithoutRemoteWrites() {
    ApplicationFormPublicationView active = view(ApplicationFormPublicationStatus.ACTIVE);
    when(state.claim("app-a", "form-a", command())).thenReturn(
        new PublicationClaim(false, null, active)
    );
    var publish = new PublishApplicationFormCommand(1, "page-a", "publish-a");
    var binding = bindingView(ApplicationFormPageBindingStatus.ACTIVE);
    when(pageBindings.claim("app-a", "form-a", publish)).thenReturn(
        new ApplicationFormPageBindingStateService.BindingClaim(false, null, binding)
    );
    when(state.get("app-a", "form-a", "publication-a")).thenReturn(active);

    var result = service.publish("app-a", "form-a", publish);

    assertThat(result.dataPublication()).isSameAs(active);
    assertThat(result.pageBinding()).isSameAs(binding);
    verify(state).get("app-a", "form-a", "publication-a");
    verify(pageResources, never()).prepare(any());
    verify(pageResources, never()).activate(any());
    verify(dnaResources, never()).bind(any(), any());
  }

  private static PrepareApplicationFormDataCommand command() {
    return new PrepareApplicationFormDataCommand(1, "publish-a");
  }

  private static PublicationWork work() {
    return new PublicationWork(
        "publication-a",
        "token-a",
        "app-a",
        "form-a",
        "Orders",
        null,
        1,
        false,
        new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(), List.of()),
        "a".repeat(64)
    );
  }

  private static PreparedDnaSchema prepared(final SchemaMigrationStatus status) {
    return new PreparedDnaSchema("model-a", 1, "migration-a", status);
  }

  private static ApplicationFormPublicationView view(
      final ApplicationFormPublicationStatus status
  ) {
    return new ApplicationFormPublicationView(
        "publication-a",
        "form-a",
        1,
        status,
        ApplicationFormPublicationStep.DNA_SCHEMA,
        null,
        null,
        1,
        Instant.EPOCH,
        Instant.EPOCH
    );
  }

  private static ApplicationFormPageBindingView bindingView(
      final ApplicationFormPageBindingStatus status
  ) {
    return new ApplicationFormPageBindingView(
        "binding-a",
        "app-a",
        "page-a",
        "form-a",
        1,
        "main",
        status,
        null,
        null,
        null,
        null,
        1,
        null,
        Instant.EPOCH,
        Instant.EPOCH
    );
  }

  private static DnaInvocationContext context() {
    return new DnaInvocationContext(
        "tenant-a",
        "user-a",
        null,
        null,
        true,
        Set.of(),
        null,
        Set.of(),
        false,
        List.of(),
        Map.of()
    );
  }
}
