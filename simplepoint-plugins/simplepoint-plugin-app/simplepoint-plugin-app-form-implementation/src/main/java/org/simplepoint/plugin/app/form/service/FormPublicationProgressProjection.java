package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationProgress;
import org.simplepoint.plugin.app.form.entity.FormWritePolicyPublicationStatus;
import org.simplepoint.plugin.app.form.service.FormWritePolicyPublicationStateService.PreparedPolicy;

/** Exposes only acknowledged counters, without target identities, commands or record values. */
final class FormPublicationProgressProjection {
  private FormPublicationProgressProjection() {}

  static ApplicationFormPublicationProgress project(final PreparedPolicy policy) {
    var progress = policy.status() == FormWritePolicyPublicationStatus.CANCELLING
        ? policy.cancellation().progress() : policy.inspection().progress();
    return new ApplicationFormPublicationProgress(progress.contractVersion(),
        ApplicationFormPublicationProgress.Phase.valueOf(progress.phase().name()), Long.toString(progress.sequence()),
        Long.toString(progress.completedRecords()), Long.toString(progress.totalRecords()));
  }
}
