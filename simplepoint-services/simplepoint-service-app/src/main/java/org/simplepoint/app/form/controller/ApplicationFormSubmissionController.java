package org.simplepoint.app.form.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionPreparation;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormWorkflowDefinitions;
import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.simplepoint.plugin.app.form.service.ApplicationFormSubmissionService;
import org.simplepoint.plugin.app.form.service.FormSubmissionPreparationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Page-owned accepted submissions; domain authorization resolves every page, actor and form
 * binding.
 */
@RestController
@RequestMapping("/runtime/applications/{applicationId}/pages/{pageId}/submissions")
@PreAuthorize("isAuthenticated()")
public class ApplicationFormSubmissionController {
  private final ApplicationFormSubmissionService submissions;
  private final FormSubmissionPreparationService preparation;

  public ApplicationFormSubmissionController(
      ApplicationFormSubmissionService submissions, FormSubmissionPreparationService preparation) {
    this.submissions = submissions;
    this.preparation = preparation;
  }

  @GetMapping("/preparation")
  public Response<ApplicationFormSubmissionPreparation> prepare(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @RequestParam(name = "definitionId") String definitionId,
      @RequestParam(name = "definitionVersion") int definitionVersion,
      @RequestParam(required = false, name = "previousSubmissionId") String previousSubmissionId) {
    return Response.okay(
        preparation.prepare(
            applicationId, pageId, definitionId, definitionVersion, previousSubmissionId));
  }

  @PostMapping
  public Response<ApplicationFormSubmissionView> submit(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @RequestBody SubmitApplicationFormCommand command) {
    return Response.okay(submissions.submit(applicationId, pageId, command));
  }

  @GetMapping("/definitions")
  public Response<ApplicationFormWorkflowDefinitions> definitions(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @RequestParam(defaultValue = "", name = "keyword") String keyword,
      @RequestParam(defaultValue = "0", name = "page") int page,
      @RequestParam(defaultValue = "20", name = "size") int size) {
    return Response.okay(submissions.definitions(applicationId, pageId, keyword, page, size));
  }

  @PostMapping("/{id}/retry")
  public Response<ApplicationFormSubmissionView> retry(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "id") String id) {
    return Response.okay(submissions.retry(applicationId, pageId, id));
  }

  @GetMapping("/{id}")
  public Response<ApplicationFormSubmissionView> get(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "id") String id) {
    return Response.okay(submissions.get(applicationId, pageId, id));
  }
}
