package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionPreparation;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormWorkflowDefinitions;
import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity;
import org.simplepoint.plugin.app.form.service.FormInverseRelationResolver.ResolvedView;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;
import org.springframework.stereotype.Service;

/** Related entries use the same durable submission journal with an immutable data-write scope. */
@Service
public class FormInverseSubmissionService {
  private final FormInverseRelationResolver views;
  private final ApplicationFormSubmissionService submissions;
  private final FormSubmissionPreparationService preparation;

  /** Uses domain-owned relation resolution and the standard submission lifecycle. */
  public FormInverseSubmissionService(
      FormInverseRelationResolver views,
      ApplicationFormSubmissionService submissions,
      FormSubmissionPreparationService preparation) {
    this.views = views;
    this.submissions = submissions;
    this.preparation = preparation;
  }

  /** Lists processes bound to the exact authorized source publication. */
  public ApplicationFormWorkflowDefinitions definitions(
      String applicationId,
      String pageId,
      FormInverseViewIdentity identity,
      String keyword,
      int page,
      int size) {
    return submissions.definitions(
        resolve(applicationId, pageId, identity).source(), keyword, page, size);
  }

  /** Prepares the existing source workflow without accepting replacement business values. */
  public ApplicationFormSubmissionPreparation prepare(
      String applicationId,
      String pageId,
      FormInverseViewIdentity identity,
      String definitionId,
      int definitionVersion) {
    return preparation.prepare(
        resolve(applicationId, pageId, identity).source(), definitionId, definitionVersion);
  }

  /** Membership is verified inside DNA capture, including restart and response-loss recovery. */
  public ApplicationFormSubmissionView submit(
      String applicationId,
      String pageId,
      FormInverseViewIdentity identity,
      SubmitApplicationFormCommand command) {
    var view = resolve(applicationId, pageId, identity);
    return submissions.submitRelated(view.source(), scope(view), command);
  }

  /** A scoped entry cannot inspect another parent entry's accepted operation. */
  public ApplicationFormSubmissionView get(
      String applicationId, String pageId, FormInverseViewIdentity identity, String id) {
    var view = resolve(applicationId, pageId, identity);
    return submissions.getRelated(view.source(), scope(view), id);
  }

  private ResolvedView resolve(
      String applicationId, String pageId, FormInverseViewIdentity identity) {
    if (identity == null) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_INVALID, "A related entry is required");
    }
    var view =
        views.resolve(
            applicationId, pageId, identity.runtime(), identity.viewId(), identity.recordId());
    if (!view.view().allowEdit()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "The related entry does not allow submission");
    }
    return view;
  }

  private static RelatedRecordScope scope(ResolvedView view) {
    return new RelatedRecordScope(
        "1",
        view.relation().relationId(),
        view.parent().physicalModelId(),
        view.parentRecordId(),
        RelatedRecordScope.Mode.MAINTAIN,
        null);
  }
}
