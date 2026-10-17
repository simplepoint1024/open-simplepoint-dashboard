package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.FormPageResourceCoordinator.PreparedPageResource;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Service;

/** Projects an APP page resource into DNA before the page becomes reachable. */
@Service
public class FormDnaResourceBindingCoordinator {

  private final DnaFormRuntimeService dna;
  private final DnaInvocationContextFactory contextFactory;

  /** Creates the coordinator over the narrow trusted DNA contract. */
  public FormDnaResourceBindingCoordinator(
      final DnaFormRuntimeService dna,
      final DnaInvocationContextFactory contextFactory
  ) {
    this.dna = dna;
    this.contextFactory = contextFactory;
  }

  /** Idempotently binds the prepared page code to the active form model. */
  public PhysicalModelResourceBindingView bind(
      final BindingWork work,
      final PreparedPageResource resource
  ) {
    try {
      var context = contextFactory.current();
      PhysicalModelView model = dna.getModel(context, work.physicalModelId());
      requireModel(work, model);
      PhysicalModelResourceBindingView binding = dna.projectModelResource(
          context,
          new PhysicalModelResourceProjectionCommand(
              model.id(),
              resource.resourceCode(),
              "app:" + work.managedKey(),
              work.generation()
          )
      );
      if (binding == null || !model.id().equals(binding.physicalModelId())
          || !resource.resourceCode().equals(binding.resourceCode())) {
        throw new IllegalStateException("DNA returned an inconsistent resource binding");
      }
      return binding;
    } catch (DnaRuntimeException exception) {
      throw new ApplicationFormException(
          exception.errorCode() == DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED
              ? ApplicationFormErrorCode.PAGE_BINDING_SUPERSEDED : ApplicationFormErrorCode.PUBLICATION_FAILED,
          exception.errorCode() == DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED
              ? "A newer publication has replaced this page intent" : "DNA page resource projection failed",
          exception);
    } catch (ApplicationFormException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "DNA page resource binding failed",
          exception
      );
    }
  }

  private static void requireModel(
      final BindingWork work,
      final PhysicalModelView model
  ) {
    if (model == null
        || !work.physicalModelId().equals(model.id())
        || !work.applicationId().equals(model.appId())
        || !work.formId().equals(model.formId())
        || model.status() != PhysicalModelStatus.ACTIVE
        || model.activeVersion() == null) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "The active DNA model does not match the APP form"
      );
    }
  }
}
