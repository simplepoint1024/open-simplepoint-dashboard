package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormReleaseView;
import org.simplepoint.plugin.app.form.api.model.PrepareApplicationFormDataCommand;
import org.simplepoint.plugin.app.form.api.model.PublishApplicationFormCommand;

/** Durable APP publication boundary advanced in independently recoverable steps. */
public interface ApplicationFormPublicationService {

  /** Prepares the immutable form version's physical DNA schema. */
  ApplicationFormPublicationView prepareData(
      String applicationId,
      String formId,
      PrepareApplicationFormDataCommand command
  );

  /** Publishes a version's data schema and activates its page binding when ready. */
  ApplicationFormReleaseView publish(
      String applicationId,
      String formId,
      PublishApplicationFormCommand command
  );

  /** Resumes the persisted page/version intent; the browser supplies only its identity. */
  ApplicationFormReleaseView resume(String applicationId, String formId, String releaseId);

  /** Cancels an eligible stored attempt or resolves a publication that already committed. */
  ApplicationFormReleaseView cancel(String applicationId, String formId, String releaseId);

  /** Reads aggregate feedback for the saved attempt under publication authority. */
  ApplicationFormPublicationInspection inspection(String applicationId, String formId, String releaseId);

  /** Reads one publication after checking its application and form ownership. */
  ApplicationFormPublicationView get(
      String applicationId,
      String formId,
      String publicationId
  );
}
