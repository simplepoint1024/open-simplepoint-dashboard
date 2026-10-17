package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationStatus;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormReleaseView;
import org.simplepoint.plugin.app.form.api.model.PrepareApplicationFormDataCommand;
import org.simplepoint.plugin.app.form.api.model.PublishApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormPublicationService;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingClaim;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.ApplicationFormPublicationStateService.PublicationClaim;
import org.simplepoint.plugin.app.form.service.ApplicationFormPublicationStateService.PublicationWork;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.DnaSchemaPreparationRequest;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.PreparedDnaSchema;
import org.simplepoint.plugin.app.form.service.FormPageResourceCoordinator.PreparedPageResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Executes remote DNA work outside APP transactions and durably records each result. */
@Service
public class ApplicationFormPublicationServiceImpl implements ApplicationFormPublicationService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ApplicationFormPublicationServiceImpl.class);

  private final ApplicationFormPublicationStateService state;
  private final FormDnaSchemaPublicationCoordinator dna;
  private final DnaInvocationContextFactory contextFactory;
  private final ApplicationFormPageBindingStateService pageBindings;
  private final FormPageResourceCoordinator pageResources;
  private final FormDnaResourceBindingCoordinator dnaResources;
  private final ApplicationAccessPort applicationAccess;
  private final FormRequestContext requestContext;
  private final FormWritePolicyPublicationCoordinator policies;
  private final FormPublicationImpactService impacts;
  private final FormPublicationInspectionService inspections;
  private final FormDetailSchemaCoordinator details;

  /** Creates the publication orchestrator from short state and remote DNA boundaries. */
  public ApplicationFormPublicationServiceImpl(
      final ApplicationFormPublicationStateService state,
      final FormDnaSchemaPublicationCoordinator dna,
      final DnaInvocationContextFactory contextFactory,
      final ApplicationFormPageBindingStateService pageBindings,
      final FormPageResourceCoordinator pageResources,
      final FormDnaResourceBindingCoordinator dnaResources,
      final ApplicationAccessPort applicationAccess,
      final FormRequestContext requestContext,
      final FormWritePolicyPublicationCoordinator policies,
      final FormPublicationImpactService impacts,
      final FormPublicationInspectionService inspections,
      final FormDetailSchemaCoordinator details) {
    this.state = state;
    this.dna = dna;
    this.contextFactory = contextFactory;
    this.pageBindings = pageBindings;
    this.pageResources = pageResources;
    this.dnaResources = dnaResources;
    this.applicationAccess = applicationAccess;
    this.requestContext = requestContext;
    this.policies = policies;
    this.impacts = impacts;
    this.inspections = inspections;
    this.details = details;
  }

  @Override
  public ApplicationFormReleaseView publish(
      final String applicationId,
      final String formId,
      final PublishApplicationFormCommand command) {
    if (command == null) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.DOCUMENT_INVALID, "Publication command is required");
    }
    String appId = requireApplicationAccess(applicationId);
    var intent = pageBindings.reserve(appId, formId, command);
    if (intent.status() == ApplicationFormPageBindingStatus.ACTIVE) {
      var projection = pageBindings.activeProjection(appId, formId, intent.id());
      dnaResources.bind(projection.work(), projection.resource());
      pageResources.refreshPermissions(projection.work());
    }
    if (intent.status() == ApplicationFormPageBindingStatus.ACTIVE
        || intent.status() == ApplicationFormPageBindingStatus.RETIRED
        || intent.status() == ApplicationFormPageBindingStatus.CANCELLED) {
      return new ApplicationFormReleaseView(
          state.getForVersion(appId, formId, command.formVersion()), intent);
    }
    BindingClaim recovery = pageBindings.claimPolicyRecovery(appId, formId, intent.id());
    if (recovery != null) {
      return recoverPolicy(appId, formId, recovery, false);
    }
    ApplicationFormPublicationView data;
    try {
      data =
          prepareData(
              appId,
              formId,
              new PrepareApplicationFormDataCommand(
                  command.formVersion(), command.idempotencyKey()));
    } catch (RuntimeException exception) {
      try {
        pageBindings.preparationFailed(
            intent.id(),
            exception instanceof ApplicationFormException failure
                ? failure.errorCode()
                : ApplicationFormErrorCode.PUBLICATION_FAILED);
      } catch (RuntimeException persistenceFailure) {
        exception.addSuppressed(persistenceFailure);
      }
      throw exception;
    }
    if (data.status() != ApplicationFormPublicationStatus.DATA_READY
        && data.status() != ApplicationFormPublicationStatus.ACTIVE) {
      return new ApplicationFormReleaseView(data, intent);
    }
    BindingClaim claim = pageBindings.claim(applicationId, formId, command);
    if (!claim.execute()) {
      return new ApplicationFormReleaseView(
          activeData(applicationId, formId, data, claim.view().status()), claim.view());
    }
    BindingWork work = claim.work();
    try {
      PreparedPageResource prepared = pageResources.prepare(work);
      pageBindings.resourceReady(work.bindingId(), work.executionToken(), prepared);
      if (work.policyProtocolVersion() == 1) {
        var policy = policies.prepareTarget(work, impacts.requirements(work));
        if (policy.status() == FormWritePolicyPublicationStatus.PREPARED) {
          return blocked(work, data);
        }
        if (policy.status() == FormWritePolicyPublicationStatus.CANCELLED) {
          return new ApplicationFormReleaseView(
              data, pageBindings.get(appId, formId, work.bindingId()));
        }
        if (policy.status() != FormWritePolicyPublicationStatus.TARGET_READY) {
          throw new ApplicationFormException(
              ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
              "The target decision is not confirmed");
        }
      }
      dnaResources.bind(work, prepared);
      PreparedPageResource active = pageResources.activate(work);
      var binding = pageBindings.complete(work.bindingId(), work.executionToken(), active);
      return new ApplicationFormReleaseView(
          activeData(applicationId, formId, data, binding.status()), binding);
    } catch (ApplicationFormException exception) {
      recordBindingFailure(work, exception.errorCode(), exception);
      throw exception;
    } catch (RuntimeException exception) {
      ApplicationFormException failure =
          new ApplicationFormException(
              ApplicationFormErrorCode.PUBLICATION_FAILED,
              "Form page publication failed",
              exception);
      recordBindingFailure(work, failure.errorCode(), failure);
      throw failure;
    }
  }

  @Override
  public ApplicationFormReleaseView resume(
      final String applicationId, final String formId, final String releaseId) {
    String appId = requireApplicationAccess(applicationId);
    return publish(appId, formId, pageBindings.resumeCommand(appId, formId, releaseId));
  }

  @Override
  public ApplicationFormReleaseView cancel(
      final String applicationId, final String formId, final String releaseId) {
    String appId = requireApplicationAccess(applicationId);
    BindingClaim claim = pageBindings.claimCancellation(appId, formId, releaseId);
    return recoverPolicy(appId, formId, claim, true);
  }

  @Override
  public ApplicationFormPublicationInspection inspection(
      final String applicationId, final String formId, final String releaseId) {
    String appId = requireApplicationAccess(applicationId);
    pageBindings.get(appId, formId, releaseId);
    return inspections.get(appId, formId, releaseId);
  }

  private ApplicationFormReleaseView blocked(
      final BindingWork work, final ApplicationFormPublicationView data) {
    var binding =
        pageBindings.fail(
            work.bindingId(),
            work.executionToken(),
            ApplicationFormErrorCode.PUBLICATION_DATA_CONFLICT);
    return new ApplicationFormReleaseView(
        data, binding, inspections.get(work.applicationId(), work.formId(), work.bindingId()));
  }

  private ApplicationFormReleaseView recoverPolicy(
      final String appId, final String formId, final BindingClaim claim, final boolean cancelling) {
    var data = state.getForVersion(appId, formId, claim.view().formVersion());
    if (!claim.execute()) {
      return new ApplicationFormReleaseView(data, claim.view());
    }
    BindingWork work = claim.work();
    try {
      var policy = cancelling ? policies.cancelTarget(work) : policies.resumeTarget(work);
      if (policy != null && policy.status() == FormWritePolicyPublicationStatus.PREPARED) {
        return blocked(work, data);
      }
      if (policy != null && policy.status() == FormWritePolicyPublicationStatus.CANCELLED) {
        return new ApplicationFormReleaseView(
            data, pageBindings.get(appId, formId, work.bindingId()));
      }
      if (policy == null || policy.status() != FormWritePolicyPublicationStatus.TARGET_READY) {
        throw new ApplicationFormException(
            ApplicationFormErrorCode.PUBLICATION_IN_PROGRESS,
            "The target decision is not confirmed");
      }
      // The target published first. Finish that exact attempt instead of reporting a successful
      // cancellation.
      var resource = pageResources.prepare(work);
      dnaResources.bind(work, resource);
      var active = pageResources.activate(work);
      var binding = pageBindings.complete(work.bindingId(), work.executionToken(), active);
      return new ApplicationFormReleaseView(
          activeData(appId, formId, data, binding.status()), binding);
    } catch (RuntimeException exception) {
      recordBindingFailure(
          work,
          exception instanceof ApplicationFormException failure
              ? failure.errorCode()
              : ApplicationFormErrorCode.PUBLICATION_FAILED,
          exception);
      throw exception;
    }
  }

  @Override
  public ApplicationFormPublicationView prepareData(
      final String applicationId,
      final String formId,
      final PrepareApplicationFormDataCommand command) {
    String appId = requireApplicationAccess(applicationId);
    PublicationClaim claim = state.claim(appId, formId, command);
    if (!claim.execute()) {
      return claim.view();
    }
    PublicationWork work = claim.work();
    try {
      PreparedDnaSchema prepared =
          dna.prepare(
              contextFactory.current(),
              new DnaSchemaPreparationRequest(
                  work.applicationId(),
                  work.formId(),
                  work.displayName(),
                  "default",
                  work.physicalModelId(),
                  work.schema(),
                  work.storageChecksum(),
                  work.recoverStaleDna()));
      if (prepared.active() && work.document() != null && !work.document().details().isEmpty()) {
        String bindings =
            details.prepare(
                contextFactory.current(),
                work.applicationId(),
                work.formId(),
                prepared.physicalModelId(),
                work.document(),
                work.recoverStaleDna());
        if (bindings == null) {
          return state.recordDataProgress(work.publicationId(), work.executionToken(), prepared);
        }
        prepared =
            new PreparedDnaSchema(
                prepared.physicalModelId(),
                prepared.physicalModelVersion(),
                prepared.migrationId(),
                prepared.status(),
                bindings);
      }
      if (prepared.active()) {
        return state.completeData(work.publicationId(), work.executionToken(), prepared);
      }
      return state.recordDataProgress(work.publicationId(), work.executionToken(), prepared);
    } catch (ApplicationFormException exception) {
      recordFailure(work, exception.errorCode(), exception);
      throw exception;
    } catch (RuntimeException exception) {
      ApplicationFormException failure =
          new ApplicationFormException(
              ApplicationFormErrorCode.PUBLICATION_FAILED,
              "Form data publication failed",
              exception);
      recordFailure(work, failure.errorCode(), failure);
      throw failure;
    }
  }

  @Override
  public ApplicationFormPublicationView get(
      final String applicationId, final String formId, final String publicationId) {
    String appId = requireApplicationAccess(applicationId);
    return state.get(appId, formId, publicationId);
  }

  private String requireApplicationAccess(final String applicationId) {
    if (applicationId == null || applicationId.isBlank() || applicationId.trim().length() > 36) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.DOCUMENT_INVALID, "applicationId is required");
    }
    String normalized = applicationId.trim();
    FormRequestContext.FormActor actor = requestContext.requireActor();
    applicationAccess.requireApplication(actor.tenantId(), normalized);
    return normalized;
  }

  private ApplicationFormPublicationView activeData(
      final String applicationId,
      final String formId,
      final ApplicationFormPublicationView data,
      final ApplicationFormPageBindingStatus status) {
    if (status != ApplicationFormPageBindingStatus.ACTIVE) {
      return data;
    }
    return state.get(applicationId, formId, data.id());
  }

  private void recordFailure(
      final PublicationWork work,
      final ApplicationFormErrorCode errorCode,
      final RuntimeException original) {
    if (errorCode == ApplicationFormErrorCode.PUBLICATION_FAILED) {
      LOGGER.error(
          "Form data publication failed: publicationId={}", work.publicationId(), original);
    }
    try {
      state.fail(work.publicationId(), work.executionToken(), errorCode);
    } catch (RuntimeException persistenceFailure) {
      original.addSuppressed(persistenceFailure);
    }
  }

  private void recordBindingFailure(
      final BindingWork work,
      final ApplicationFormErrorCode errorCode,
      final RuntimeException original) {
    if (errorCode == ApplicationFormErrorCode.PUBLICATION_FAILED) {
      LOGGER.error("Form page publication failed: releaseId={}", work.bindingId(), original);
    }
    try {
      pageBindings.fail(work.bindingId(), work.executionToken(), errorCode);
    } catch (RuntimeException persistenceFailure) {
      original.addSuppressed(persistenceFailure);
    }
  }
}
