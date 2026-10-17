package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormView;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;

/** Shared safe metadata projection; physical placement never reaches catalog clients. */
final class ApplicationFormViews {

  private ApplicationFormViews() {
  }

  static ApplicationFormView definition(final ApplicationFormEntity form) {
    return new ApplicationFormView(
        form.getId(),
        form.getApplicationId(),
        form.getCode(),
        form.getName(),
        form.getDescription(),
        form.getStatus(),
        form.getDraftRevision(),
        form.getLatestVersion(),
        form.getPublishedVersion(),
        form.getLockVersion(),
        form.getCreatedAt(),
        form.getUpdatedAt()
    );
  }

}
