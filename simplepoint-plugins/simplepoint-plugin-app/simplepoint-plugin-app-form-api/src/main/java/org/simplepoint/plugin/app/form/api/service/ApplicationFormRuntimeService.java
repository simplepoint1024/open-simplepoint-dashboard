package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;

/** Resolves immutable form documents through active APP page bindings. */
public interface ApplicationFormRuntimeService {

  /** Returns the authorized MAIN scene runtime descriptor for one published page. */
  ApplicationFormRuntimeView page(String applicationId, String pageId);
}
