package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.model.FormCalculationCatalog;
import org.simplepoint.plugin.app.form.api.model.FormCalculationPreview;
import org.simplepoint.plugin.app.form.api.model.PreviewFormCalculationCommand;

/**
 * Form designer adapter; the independent calculation library remains usable without this service.
 */
public interface ApplicationFormCalculationService {
  /** Reads the actual engine's function descriptors after resolving the tenant-owned form. */
  FormCalculationCatalog catalog(String applicationId, String formId);

  /** Computes an unsaved design against supplied samples without any record read or write. */
  FormCalculationPreview preview(
      String applicationId, String formId, PreviewFormCalculationCommand command);
}
