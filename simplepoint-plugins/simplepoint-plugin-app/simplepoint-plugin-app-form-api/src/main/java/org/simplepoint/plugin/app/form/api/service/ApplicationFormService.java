package org.simplepoint.plugin.app.form.api.service;

import java.util.List;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormVersionView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormView;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.SaveApplicationFormDraftCommand;

/** Tenant-scoped form definition, draft and immutable version boundary. */
public interface ApplicationFormService {

  /** Creates a form with one valid empty main scene. */
  ApplicationFormView create(CreateApplicationFormCommand command);

  /** Lists forms under one authorized application in stable creation order. */
  List<ApplicationFormView> list(String applicationId);

  /** Reads one form after checking its application binding. */
  ApplicationFormView get(String applicationId, String formId);

  /** Reads the current mutable form draft. */
  ApplicationFormDraftView draft(String applicationId, String formId);

  /** Saves a complete validated draft using optimistic revision control. */
  ApplicationFormDraftView saveDraft(
      String applicationId,
      String formId,
      SaveApplicationFormDraftCommand command
  );

  /** Freezes the current draft, reusing an identical latest version. */
  ApplicationFormVersionView createVersion(
      String applicationId,
      String formId,
      long expectedRevision
  );

  /** Lists immutable versions newest first. */
  List<ApplicationFormVersionView> versions(String applicationId, String formId);
}
