package org.simplepoint.plugin.app.form.api.model;

import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;

/** Published form identity observed by the browser; never selects a physical model. */
public record ApplicationFormRuntimeIdentity(String formId, int formVersion) {

  /** Rejects stale pages before any record access or mutation. */
  public static void requireCurrent(
      final ApplicationFormRuntimeIdentity expected,
      final String activeFormId,
      final int activeFormVersion
  ) {
    if (expected == null || !activeFormId.equals(expected.formId())
        || activeFormVersion != expected.formVersion()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RUNTIME_VERSION_CONFLICT,
          "The published form has changed. Refresh the page before continuing."
      );
    }
  }
}
