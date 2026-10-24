package org.simplepoint.app.form.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionSnapshot;
import org.simplepoint.plugin.app.form.service.FormSubmissionSnapshotService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow authorization is checked remotely before current APP page and field policies are
 * applied.
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class ApplicationFormWorkflowSnapshotController {
  private final FormSubmissionSnapshotService snapshots;

  /** Uses the workflow-authorized immutable business snapshot reader. */
  public ApplicationFormWorkflowSnapshotController(FormSubmissionSnapshotService snapshots) {
    this.snapshots = snapshots;
  }

  /** Reads the submission evidence bound to an authorized task. */
  @GetMapping("/workflow/tasks/{taskId}/business-form")
  public Response<ApplicationFormSubmissionSnapshot> task(
      @PathVariable(name = "taskId") String taskId) {
    return Response.okay(snapshots.task(taskId));
  }

  /** Reads submission evidence after checking process participation. */
  @GetMapping("/workflow/instances/{instanceId}/business-form")
  public Response<ApplicationFormSubmissionSnapshot> instance(
      @PathVariable(name = "instanceId") String instanceId) {
    return Response.okay(snapshots.instance(instanceId));
  }

  /** Reads the actor's own submission under current page and field grants. */
  @GetMapping("/runtime/applications/{applicationId}/pages/{pageId}/submissions/{id}/snapshot")
  public Response<ApplicationFormSubmissionSnapshot> own(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "id") String id) {
    return Response.okay(snapshots.owned(applicationId, pageId, id));
  }
  /** Reads immutable evidence after authorizing the exact current business record. */
  @GetMapping("/runtime/applications/{applicationId}/pages/{pageId}/records/{recordId}/submissions/{id}/snapshot")
  public Response<ApplicationFormSubmissionSnapshot> record(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "recordId") String recordId,
      @PathVariable(name = "id") String id) {
    return Response.okay(snapshots.forRecord(applicationId, pageId, recordId, id));
  }

}
