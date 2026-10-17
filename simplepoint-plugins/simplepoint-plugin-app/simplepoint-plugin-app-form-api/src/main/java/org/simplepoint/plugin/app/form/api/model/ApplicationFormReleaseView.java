package org.simplepoint.plugin.app.form.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One-click publication result spanning DNA data preparation and page activation. */
public record ApplicationFormReleaseView(
    ApplicationFormPublicationView dataPublication,
    ApplicationFormPageBindingView pageBinding,
    @JsonInclude(JsonInclude.Include.NON_NULL) ApplicationFormPublicationInspection inspection
) {
  /** Retains responses for releases without an inspection failure. */
  public ApplicationFormReleaseView(final ApplicationFormPublicationView dataPublication,
      final ApplicationFormPageBindingView pageBinding) {
    this(dataPublication, pageBinding, null);
  }
}
