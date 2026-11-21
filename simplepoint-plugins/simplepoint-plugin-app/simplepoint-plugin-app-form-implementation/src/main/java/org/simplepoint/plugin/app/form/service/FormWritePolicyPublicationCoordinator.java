package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.ApplicationFormPageBindingStateService.BindingWork;
import org.simplepoint.plugin.app.form.service.FormWritePolicyPublicationStateService.PreparedPolicy;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Component;

/** Persists the exact intent before remote execution and resumes it after uncertain responses. */
@Component
public class FormWritePolicyPublicationCoordinator {

  private final FormWritePolicyPublicationStateService state;
  private final DnaFormRuntimeService dna;
  private final DnaInvocationContextFactory contexts;
  private final FormCollectionRebindAuthorization rebindAuthorization;

  /** Keeps target transactions outside APP state transactions. */
  public FormWritePolicyPublicationCoordinator(
      final FormWritePolicyPublicationStateService state,
      final DnaFormRuntimeService dna,
      final DnaInvocationContextFactory contexts,
      final FormCollectionRebindAuthorization rebindAuthorization) {
    this.state = state;
    this.dna = dna;
    this.contexts = contexts;
    this.rebindAuthorization = rebindAuthorization;
  }

  /**
   * Consumes trusted server-compiled impact requirements; retries ignore newly supplied
   * requirements.
   */
  public PreparedPolicy prepareTarget(final BindingWork work, final FormWritePolicyImpact impact) {
    if (impact != null && impact.collectionRebind() != null) {
      rebindAuthorization.requireAccess(work.tenantId(), impact.collectionRebind());
    }
    PreparedPolicy prepared = state.prepare(work, impact);
    return reconcile(work, prepared);
  }

  /**
   * Existing releases recover their original decision before any resource projection or activation.
   */
  public PreparedPolicy resumeTarget(final BindingWork work) {
    PreparedPolicy prepared = state.current(work);
    return prepared == null ? null : reconcile(work, prepared);
  }

  /**
   * Releases an acknowledged batch after its response has been persisted, never after an unknown
   * outcome.
   */
  public void releaseBatch(final BindingWork work) {
    state.releaseBatch(work);
  }

  private PreparedPolicy reconcile(final BindingWork work, final PreparedPolicy prepared) {
    if (prepared.status() == FormWritePolicyPublicationStatus.CANCELLING) {
      return cancelTarget(work);
    }
    if (prepared.status() != FormWritePolicyPublicationStatus.PREPARED) {
      return prepared;
    }
    try {
      var command = prepared.command();
      var context = contexts.current();
      return state.recordResult(
          work,
          command.collectionRebind() == null
              ? dna.publishRecordWritePolicy(context, command)
              : dna.publishCollectionRebind(context, command));
    } catch (DnaRuntimeException failure) {
      boolean rebind = prepared.command().collectionRebind() != null;
      if (failure.errorCode() == DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE
          || rebind && failure.errorCode() == DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT) {
        state.recordDataFailure(work);
        if (prepared.command().referenceConversion() != null || rebind) {
          throw new ApplicationFormException(
              ApplicationFormErrorCode.PUBLICATION_DATA_CONFLICT,
              "Existing data conflicts with the frozen relation migration",
              failure);
        }
        throw new ApplicationFormException(
            ApplicationFormErrorCode.COLLECTION_IN_USE,
            "Remove or migrate active associations before retiring the collection",
            failure);
      }
      if (failure.errorCode() == DnaRuntimeErrorCode.RECORD_COMMAND_INVALID) {
        state.recordDataFailure(work);
        throw new ApplicationFormException(
            ApplicationFormErrorCode.PUBLICATION_DATA_CONFLICT,
            "Existing data cannot satisfy the frozen calculation definition; cancel and repair"
                + " before publishing again",
            failure);
      }
      // A transport/target failure never releases the durable intent or allocates another version.
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Form policy publication must be resumed using its original intent",
          failure);
    }
  }

  /**
   * A retry always resumes the persisted decision; cancellation cannot discard an uncertain target
   * transaction.
   */
  public PreparedPolicy cancelTarget(final BindingWork work) {
    PreparedPolicy pending = state.requestCancellation(work);
    if (pending.status() != FormWritePolicyPublicationStatus.CANCELLING) {
      return pending;
    }
    try {
      var command = pending.command();
      var context = contexts.current();
      return state.recordCancellation(
          work,
          command.collectionRebind() == null
              ? dna.cancelRecordWritePolicy(context, command)
              : dna.cancelCollectionRebind(context, command));
    } catch (DnaRuntimeException failure) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.PUBLICATION_FAILED,
          "Form publication cancellation must be resumed using its original intent",
          failure);
    }
  }
}
