package org.simplepoint.app.form.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionPreparation;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormWorkflowDefinitions;
import org.simplepoint.plugin.app.form.api.model.SubmitApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity;
import org.simplepoint.plugin.app.form.service.FormInverseSubmissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated inverse entry adapter; APP resolves every source and accepted operation. */
@RestController
@RequestMapping(
    "/runtime/applications/{applicationId}/pages/{pageId}/records/{parentRecordId}"
        + "/relations/{viewId}/submissions")
@PreAuthorize("isAuthenticated()")
public class ApplicationFormInverseSubmissionController {
  private final FormInverseSubmissionService submissions;

  /** Uses the existing domain submission coordinator. */
  public ApplicationFormInverseSubmissionController(FormInverseSubmissionService submissions) {
    this.submissions = submissions;
  }

  /** Lists bound source processes. */
  @GetMapping("/definitions")
  public Response<ApplicationFormWorkflowDefinitions> definitions(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "parentRecordId") String parentRecordId,
      @PathVariable(name = "viewId") String viewId,
      @RequestParam(name = "parentFormId") String parentFormId,
      @RequestParam(name = "parentFormVersion") int parentFormVersion,
      @RequestParam(defaultValue = "", name = "keyword") String keyword,
      @RequestParam(defaultValue = "0", name = "page") int page,
      @RequestParam(defaultValue = "20", name = "size") int size) {
    var identity =
        new FormInverseViewIdentity(
            new ApplicationFormRuntimeIdentity(parentFormId, parentFormVersion),
            viewId,
            parentRecordId);
    return Response.okay(
        submissions.definitions(applicationId, pageId, identity, keyword, page, size));
  }

  /** Prepares the exact source form and process version. */
  @GetMapping("/preparation")
  public Response<ApplicationFormSubmissionPreparation> prepare(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "parentRecordId") String parentRecordId,
      @PathVariable(name = "viewId") String viewId,
      @RequestParam(name = "parentFormId") String parentFormId,
      @RequestParam(name = "parentFormVersion") int parentFormVersion,
      @RequestParam(name = "definitionId") String definitionId,
      @RequestParam(name = "definitionVersion") int definitionVersion) {
    var identity =
        new FormInverseViewIdentity(
            new ApplicationFormRuntimeIdentity(parentFormId, parentFormVersion),
            viewId,
            parentRecordId);
    return Response.okay(
        submissions.prepare(applicationId, pageId, identity, definitionId, definitionVersion));
  }

  /** Accepts a saved record confirmation with immutable relation scope. */
  @PostMapping
  public Response<ApplicationFormSubmissionView> submit(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "parentRecordId") String parentRecordId,
      @PathVariable(name = "viewId") String viewId,
      @RequestParam(name = "parentFormId") String parentFormId,
      @RequestParam(name = "parentFormVersion") int parentFormVersion,
      @RequestBody SubmitApplicationFormCommand command) {
    var identity =
        new FormInverseViewIdentity(
            new ApplicationFormRuntimeIdentity(parentFormId, parentFormVersion),
            viewId,
            parentRecordId);
    return Response.okay(submissions.submit(applicationId, pageId, identity, command));
  }

  /** Reads only the current actor and related entry accepted operation. */
  @GetMapping("/{id}")
  public Response<ApplicationFormSubmissionView> get(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "parentRecordId") String parentRecordId,
      @PathVariable(name = "viewId") String viewId,
      @RequestParam(name = "parentFormId") String parentFormId,
      @RequestParam(name = "parentFormVersion") int parentFormVersion,
      @PathVariable(name = "id") String id) {
    var identity =
        new FormInverseViewIdentity(
            new ApplicationFormRuntimeIdentity(parentFormId, parentFormVersion),
            viewId,
            parentRecordId);
    return Response.okay(submissions.get(applicationId, pageId, identity, id));
  }
}
