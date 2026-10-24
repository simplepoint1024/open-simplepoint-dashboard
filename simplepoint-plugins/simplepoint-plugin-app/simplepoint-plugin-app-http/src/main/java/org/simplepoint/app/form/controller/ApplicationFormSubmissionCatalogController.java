package org.simplepoint.app.form.controller;

import java.util.Map;
import org.simplepoint.app.form.service.ApplicationFormSubmissionCatalog;
import org.simplepoint.app.form.service.ApplicationFormSubmissionCatalog.SubmissionRow;
import org.simplepoint.core.http.Response;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform schema/query endpoints for page-owned workflow submission history. */
@RestController
@RequestMapping({"/runtime/applications/{applicationId}/pages/{pageId}/submissions",
    "/runtime/applications/{applicationId}/pages/{pageId}/records/{recordId}/submissions"})
@PreAuthorize("isAuthenticated()")
public class ApplicationFormSubmissionCatalogController {
  private final ApplicationFormSubmissionCatalog catalog;

  /** Uses the page-owned submission query service. */
  public ApplicationFormSubmissionCatalogController(ApplicationFormSubmissionCatalog catalog) {
    this.catalog = catalog;
  }

  /** Lists the current actor's submissions using the platform table query contract. */
  @GetMapping
  public Response<Page<SubmissionRow>> page(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId,
      @PathVariable(name = "recordId", required = false) String recordId,
      @RequestParam Map<String, String> filters,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable page) {
    return Response.okay(catalog.page(applicationId, pageId, recordId, filters, page));
  }

  /** Returns permission-derived submission actions and fields. */
  @GetMapping("/schema")
  public Response<Map<String, Object>> schema(
      @PathVariable(name = "applicationId") String applicationId,
      @PathVariable(name = "pageId") String pageId) {
    return Response.okay(catalog.schema(applicationId, pageId));
  }
}
