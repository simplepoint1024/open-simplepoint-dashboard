package org.simplepoint.app.form.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.FormCalculationCatalog;
import org.simplepoint.plugin.app.form.api.model.FormCalculationPreview;
import org.simplepoint.plugin.app.form.api.model.PreviewFormCalculationCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCalculationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Designer-only function catalog and sample calculation transport, separate from record writes. */
@RestController
@RequestMapping("/applications/{applicationId}/forms/{formId}/calculations")
@PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.edit')")
public class ApplicationFormCalculationController {
  private final ApplicationFormCalculationService calculations;

  /** Delegates all ownership and document checks to the form-domain adapter. */
  public ApplicationFormCalculationController(ApplicationFormCalculationService calculations) {
    this.calculations = calculations;
  }

  /** Describes the active engine without exposing implementation objects. */
  @GetMapping("/catalog")
  public Response<FormCalculationCatalog> catalog(
      @PathVariable("applicationId") String applicationId, @PathVariable("formId") String formId) {
    return Response.okay(calculations.catalog(applicationId, formId));
  }

  /** Evaluates only user-supplied examples under design authority. */
  @PostMapping("/preview")
  public Response<FormCalculationPreview> preview(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("formId") String formId,
      @RequestBody PreviewFormCalculationCommand command) {
    return Response.okay(calculations.preview(applicationId, formId, command));
  }
}
