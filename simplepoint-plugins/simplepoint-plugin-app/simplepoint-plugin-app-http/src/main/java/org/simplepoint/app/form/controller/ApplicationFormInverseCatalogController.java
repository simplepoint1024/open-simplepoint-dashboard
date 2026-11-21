package org.simplepoint.app.form.controller;

import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.relation.FormInverseViewSummary;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormInverseRecordService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authorized navigation for one saved parent; unavailable source entries are not advertised. */
@RestController
@RequestMapping(
    "/runtime/applications/{applicationId}/pages/{pageId}/records/{parentRecordId}/relations")
@PreAuthorize("isAuthenticated()")
public class ApplicationFormInverseCatalogController {
  private final ApplicationFormInverseRecordService records;

  /** Reuses the APP-owned relation directory. */
  public ApplicationFormInverseCatalogController(ApplicationFormInverseRecordService records) {
    this.records = records;
  }

  /** Resolves the observed parent publication before returning readable view labels. */
  @GetMapping
  public Response<List<FormInverseViewSummary>> views(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("parentRecordId") String parentRecordId,
      @RequestParam("parentFormId") String parentFormId,
      @RequestParam("parentFormVersion") int parentFormVersion) {
    return Response.okay(
        records.views(
            applicationId,
            pageId,
            new ApplicationFormRuntimeIdentity(parentFormId, parentFormVersion),
            parentRecordId));
  }
}
