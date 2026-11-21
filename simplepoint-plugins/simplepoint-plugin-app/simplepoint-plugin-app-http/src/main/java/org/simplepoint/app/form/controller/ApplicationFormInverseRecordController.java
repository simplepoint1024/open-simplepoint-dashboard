package org.simplepoint.app.form.controller;

import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordMutationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.UpdateApplicationFormRecordCommand;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewIdentity;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormInverseRecordService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin inverse view transport; the browser cannot select a source resource or physical field. */
@RestController
@RequestMapping(
    "/runtime/applications/{applicationId}/pages/{pageId}"
        + "/records/{parentRecordId}/relations/{viewId}/records")
@PreAuthorize("isAuthenticated()")
public class ApplicationFormInverseRecordController {
  private final ApplicationFormInverseRecordService records;

  /** Delegates all authorization and aggregate semantics to the APP domain. */
  public ApplicationFormInverseRecordController(ApplicationFormInverseRecordService records) {
    this.records = records;
  }

  /** Provides the standard source editor under the effective inverse operation policy. */
  @GetMapping("/runtime")
  public Response<org.simplepoint.plugin.app.form.api.model.relation.FormInverseRuntimeView>
      describe(
          @PathVariable("applicationId") String applicationId,
          @PathVariable("pageId") String pageId,
          @PathVariable("parentRecordId") String parentRecordId,
          @PathVariable("viewId") String viewId,
          @RequestParam("parentFormId") String parentFormId,
          @RequestParam("parentFormVersion") int parentFormVersion) {
    return Response.okay(
        records.describe(
            applicationId,
            pageId,
            identity(parentFormId, parentFormVersion, viewId, parentRecordId)));
  }

  /** Returns the standard permission-filtered table schema. */
  @GetMapping("/schema")
  public Response<Map<String, Object>> schema(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion) {
    return Response.okay(
        records.schema(
            applicationId,
            pageId,
            identity(parentFormId, parentFormVersion, viewId, parentRecordId)));
  }

  /** Reads a related page with fixed server-side membership. */
  @PostMapping("/query")
  public Response<ApplicationFormRecordPageView> page(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion,
      @RequestBody ApplicationFormRecordPageQuery query) {
    return Response.okay(
        records.page(
            applicationId,
            pageId,
            identity(parentFormId, parentFormVersion, viewId, parentRecordId),
            query));
  }

  /** Reads a related record under the source entry policy. */
  @GetMapping("/{recordId}")
  public Response<ApplicationFormRecordView> get(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion,
      @PathVariable("recordId") String recordId) {
    return Response.okay(
        records.get(
            applicationId,
            pageId,
            identity(parentFormId, parentFormVersion, viewId, parentRecordId),
            recordId));
  }

  /** Creates through the standard source form lifecycle. */
  @PostMapping
  public Response<ApplicationFormRecordMutationView> create(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion,
      @RequestBody CreateApplicationFormRecordCommand command) {
    return Response.okay(
        records.create(
            applicationId,
            pageId,
            identity(parentFormId, parentFormVersion, viewId, parentRecordId),
            command));
  }

  /** Edits without permitting an implicit parent rebind. */
  @PutMapping("/{recordId}")
  public Response<ApplicationFormRecordMutationView> update(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion,
      @PathVariable("recordId") String recordId,
      @RequestBody UpdateApplicationFormRecordCommand command) {
    return Response.okay(
        records.update(
            applicationId,
            pageId,
            identity(parentFormId, parentFormVersion, viewId, parentRecordId),
            recordId,
            command));
  }

  /** Clears one nullable reference without deleting its owning record. */
  @PostMapping("/{recordId}/unlink")
  public Response<ApplicationFormRecordMutationView> unlink(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion,
      @PathVariable("recordId") String recordId,
      @RequestBody UpdateApplicationFormRecordCommand command,
      @RequestParam(value = "sourceRowId", required = false) String sourceRowId) {
    return Response.okay(
        records.unlink(
            applicationId,
            pageId,
            identity(parentFormId, parentFormVersion, viewId, parentRecordId),
            recordId,
            command,
            sourceRowId));
  }

  /** Deletes with an exact scoped idempotency key and source form version. */
  @DeleteMapping("/{recordId}")
  public Response<Void> delete(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion,
      @PathVariable("recordId") String recordId,
      @RequestParam("expectedVersion") long expectedVersion,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion,
      @RequestParam("idempotencyKey") String idempotencyKey) {
    records.delete(
        applicationId,
        pageId,
        identity(parentFormId, parentFormVersion, viewId, parentRecordId),
        recordId,
        expectedVersion,
        new ApplicationFormRuntimeIdentity(formId, formVersion),
        idempotencyKey);
    return Response.okay();
  }

  private static FormInverseViewIdentity identity(
      String formId, int version, String viewId, String parentRecordId) {
    return new FormInverseViewIdentity(
        new ApplicationFormRuntimeIdentity(formId, version), viewId, parentRecordId);
  }
}
