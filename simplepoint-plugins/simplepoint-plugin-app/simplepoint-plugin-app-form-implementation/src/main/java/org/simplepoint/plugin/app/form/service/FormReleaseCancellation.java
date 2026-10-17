package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormPageBindingStatus;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;

/** Shared cancellation eligibility for commands and the authorized release catalog. */
public final class FormReleaseCancellation {
  private FormReleaseCancellation() {
  }

  /** Legacy resource work has no target decision protocol and must be recovered instead. */
  public static boolean allowed(final ApplicationFormPageBindingStatus status, final int attempt,
      final FormWritePolicyPublicationStatus policy) {
    if (status == ApplicationFormPageBindingStatus.ACTIVE || status == ApplicationFormPageBindingStatus.RETIRED
        || status == ApplicationFormPageBindingStatus.CANCELLED) {
      return false;
    }
    return policy == FormWritePolicyPublicationStatus.PREPARED || policy == FormWritePolicyPublicationStatus.CANCELLING
        || policy == null && attempt == 0
        && (status == ApplicationFormPageBindingStatus.DATA_PREPARING || status == ApplicationFormPageBindingStatus.FAILED);
  }
}
