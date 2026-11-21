package org.simplepoint.app.form.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingQuery;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceMappingView;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceMappingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Transport-only mapping observation; APP derives target identities and authorization. */
@RestController
@RequestMapping(
    "/runtime/applications/{applicationId}/pages/{pageId}/references/{relationId}/mapping")
@PreAuthorize("isAuthenticated()")
public class ApplicationFormReferenceMappingController {
  private final ApplicationFormReferenceMappingService mappings;

  /** Uses the owning domain boundary rather than reading physical resources in HTTP adapters. */
  public ApplicationFormReferenceMappingController(
      ApplicationFormReferenceMappingService mappings) {
    this.mappings = mappings;
  }

  /** Returns a preview observation; every subsequent write independently revalidates it. */
  @PostMapping
  public Response<FormReferenceMappingView> observe(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("relationId") String relationId,
      @RequestBody FormReferenceMappingQuery query) {
    return Response.okay(mappings.observe(applicationId, pageId, relationId, query));
  }
}
