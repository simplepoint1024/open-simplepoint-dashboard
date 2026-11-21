package org.simplepoint.app.form.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveView;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceLiveService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Transport-only current display projection; original target policy remains domain-owned. */
@RestController
@RequestMapping("/runtime/applications/{applicationId}/pages/{pageId}/references/{relationId}/live")
@PreAuthorize("isAuthenticated()")
public class ApplicationFormReferenceLiveController {
  private final ApplicationFormReferenceLiveService service;

  /** Delegates to the same domain adapter in standalone and microservice deployments. */
  public ApplicationFormReferenceLiveController(ApplicationFormReferenceLiveService service) {
    this.service = service;
  }

  /** Reads current values without creating a receipt or accepting any browser-authored output. */
  @PostMapping
  public Response<FormReferenceLiveView> read(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("relationId") String relationId,
      @RequestBody FormReferenceLiveQuery query) {
    return Response.okay(service.read(applicationId, pageId, relationId, query));
  }

  /** Coalesces detail-row display reads while retaining individual target row authorization. */
  @PostMapping("/resolve")
  public Response<java.util.List<FormReferenceLiveView>> readBatch(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("relationId") String relationId,
      @RequestBody
          org.simplepoint.plugin.app.form.api.model.relation.FormReferenceLiveBatchQuery query) {
    return Response.okay(service.readBatch(applicationId, pageId, relationId, query));
  }
}
