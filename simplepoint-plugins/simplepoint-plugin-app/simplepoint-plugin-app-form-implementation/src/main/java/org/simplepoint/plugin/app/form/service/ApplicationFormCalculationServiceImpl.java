package org.simplepoint.plugin.app.form.service;

import java.time.Clock;
import org.simplepoint.plugin.app.form.api.model.FormCalculationCatalog;
import org.simplepoint.plugin.app.form.api.model.FormCalculationPreview;
import org.simplepoint.plugin.app.form.api.model.PreviewFormCalculationCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCalculationService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.springframework.stereotype.Service;

/** Tenant-scoped designer trials sharing the authoritative typed calculation compiler. */
@Service
public class ApplicationFormCalculationServiceImpl implements ApplicationFormCalculationService {
  private final ApplicationFormService forms;
  private final FormDocumentValidator validator;
  private final FormCalculationCompiler compiler = new FormCalculationCompiler(Clock.systemUTC());
  private final FormCalculationCatalog catalog =
      new FormCalculationCatalog(
          "1",
          compiler
              .functions().stream()
                  .map(
                      function ->
                          new FormCalculationCatalog.Function(
                              function.name(),
                              function.category(),
                              function.minArguments(),
                              function.maxArguments(),
                              function.resultType(),
                              function.parameters()))
                  .toList());

  /** Uses the existing form ownership resolver and bounded document validator. */
  public ApplicationFormCalculationServiceImpl(
      ApplicationFormService forms, FormDocumentValidator validator) {
    this.forms = forms;
    this.validator = validator;
  }

  @Override
  public FormCalculationCatalog catalog(String applicationId, String formId) {
    forms.get(applicationId, formId);
    return catalog;
  }

  @Override
  public FormCalculationPreview preview(
      String applicationId, String formId, PreviewFormCalculationCommand command) {
    forms.get(applicationId, formId);
    validator.validate(command.document());
    var result = compiler.compile(command.document()).evaluate(command.values(), command.details());
    return new FormCalculationPreview(result.parent(), result.details());
  }
}
