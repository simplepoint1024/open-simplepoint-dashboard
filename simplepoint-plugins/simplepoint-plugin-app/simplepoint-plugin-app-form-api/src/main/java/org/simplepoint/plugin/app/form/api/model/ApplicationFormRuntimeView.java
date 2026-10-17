package org.simplepoint.plugin.app.form.api.model;

/** Immutable runtime descriptor resolved from the active page binding. */
public record ApplicationFormRuntimeView(
    String applicationId,
    String pageId,
    String pageName,
    String formId,
    String formName,
    int formVersion,
    String sceneId,
    String resourceCode,
    ApplicationFormRuntimeCapabilities capabilities,
    ApplicationFormRuntimeDocument document,
    int writePolicyVersion
) {
  /** Keeps single-version consumers compatible with the explicit policy descriptor. */
  public ApplicationFormRuntimeView(
      String applicationId, String pageId, String pageName, String formId, String formName, int formVersion,
      String sceneId, String resourceCode, ApplicationFormRuntimeCapabilities capabilities,
      ApplicationFormRuntimeDocument document
  ) {
    this(applicationId, pageId, pageName, formId, formName, formVersion, sceneId, resourceCode,
        capabilities, document, formVersion);
  }
}
