package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.FormWritePolicyPublicationStateService.PreparedPolicy;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Component;

/** Persists the exact intent before remote execution and resumes it after uncertain responses. */
@Component
public class FormWritePolicyPublicationCoordinator {

  private final FormWritePolicyPublicationStateService state;
  private final DnaFormRuntimeService dna;
  private final DnaInvocationContextFactory contexts;

  /** Keeps target transactions outside APP state transactions. */
  public FormWritePolicyPublicationCoordinator(final FormWritePolicyPublicationStateService state,
      final DnaFormRuntimeService dna, final DnaInvocationContextFactory contexts) {
    this.state = state;
    this.dna = dna;
    this.contexts = contexts;
  }

  /** Consumes trusted server-compiled impact requirements; retries ignore newly supplied requirements. */
  public PreparedPolicy prepareTarget(final BindingWork work, final FormWritePolicyImpact impact) {
    PreparedPolicy prepared = state.prepare(work, impact);
    return reconcile(work, prepared);
  }

  /** Existing releases recover their original decision before any resource projection or activation. */
  public PreparedPolicy resumeTarget(final BindingWork work) {
    PreparedPolicy prepared = state.current(work);
    return prepared == null ? null : reconcile(work, prepared);
  }

  private PreparedPolicy reconcile(final BindingWork work, final PreparedPolicy prepared) {
    if (prepared.status() == FormWritePolicyPublicationStatus.CANCELLING) {
      return cancelTarget(work);
    }
    if (prepared.status() != FormWritePolicyPublicationStatus.PREPARED) {
      return prepared;
    }
    try {
      return state.recordResult(work, dna.publishRecordWritePolicy(contexts.current(), prepared.command()));
    } catch (DnaRuntimeException failure) {
      // A transport/target failure never releases the durable intent or allocates another version.
      throw new ApplicationFormException(ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Form policy publication must be resumed using its original intent", failure);
    }
  }

  /** A retry always resumes the persisted decision; cancellation cannot discard an uncertain target transaction. */
  public PreparedPolicy cancelTarget(final BindingWork work) {
    PreparedPolicy pending = state.requestCancellation(work);
    if (pending.status() != FormWritePolicyPublicationStatus.CANCELLING) {
      return pending;
    }
    try {
      return state.recordCancellation(work, dna.cancelRecordWritePolicy(contexts.current(), pending.command()));
    } catch (DnaRuntimeException failure) {
      throw new ApplicationFormException(ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Form publication cancellation must be resumed using its original intent", failure);
    }
  }
}
