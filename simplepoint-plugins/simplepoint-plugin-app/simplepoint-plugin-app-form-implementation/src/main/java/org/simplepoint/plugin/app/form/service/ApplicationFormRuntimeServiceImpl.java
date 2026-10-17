package org.simplepoint.plugin.app.form.service;

import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;
import org.simplepoint.plugin.app.form.api.model.FormSceneType;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRuntimeService;
import org.simplepoint.plugin.app.form.service.PublishedApplicationFormResolver.PublishedApplicationForm;
import org.springframework.stereotype.Service;

/** Resolves immutable form runtime documents through the active APP page binding. */
@Service
public class ApplicationFormRuntimeServiceImpl implements ApplicationFormRuntimeService {

  private final PublishedApplicationFormResolver resolver;
  private final FormRuntimeDocumentCompiler documents;

  /** Creates the runtime descriptor service over the shared page resolver. */
  public ApplicationFormRuntimeServiceImpl(
      final PublishedApplicationFormResolver resolver,
      final FormRuntimeDocumentCompiler documents
  ) {
    this.resolver = resolver;
    this.documents = documents;
  }

  @Override
  public ApplicationFormRuntimeView page(
      final String applicationId,
      final String pageId
  ) {
    PublishedApplicationForm form = resolver.resolve(applicationId, pageId);
    return describe(form, null);
  }

  /** Projects a resolved historical or current form through the current field policy. */
  ApplicationFormRuntimeView describe(PublishedApplicationForm form, String sceneId) {
    return new ApplicationFormRuntimeView(
        form.applicationId(),
        form.pageId(),
        form.pageName(),
        form.formId(),
        form.formName(),
        form.formVersion(),
        sceneId != null ? sceneId : form.document().scenes().stream()
            .filter(scene -> scene.type() == FormSceneType.MAIN)
            .findFirst().orElseThrow().sceneId(),
        form.resourceCode(),
        resolver.capabilities(form),
        documents.compile(form),
        form.writePolicyVersion()
    );
  }
}
