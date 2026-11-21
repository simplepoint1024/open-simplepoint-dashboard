package org.simplepoint.app.form.controller;

import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetMetadata;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTargetPage;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRelationCatalogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Source-scoped designer directory; all discovery and field policies remain in the owning domain.
 */
@RestController
@RequestMapping("/applications/{applicationId}/forms/{formId}/relation-targets")
@PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
public class ApplicationFormRelationCatalogController {
  private final ApplicationFormRelationCatalogService catalog;

  /** Delegates discovery to the application-owned authorization boundary. */
  public ApplicationFormRelationCatalogController(ApplicationFormRelationCatalogService catalog) {
    this.catalog = catalog;
  }

  /** Searches published resources available to the designer. */
  @GetMapping
  public Response<FormRelationTargetPage> search(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("formId") String formId,
      @RequestParam(value = "keyword", defaultValue = "") String keyword,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "50") int size) {
    return Response.okay(catalog.search(applicationId, formId, keyword, page, size));
  }

  /** Resolves selected labels under the same source scope. */
  @PostMapping("/resolve")
  public Response<List<FormRelationTargetOption>> resolve(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("formId") String formId,
      @RequestBody List<String> resourceIds) {
    return Response.okay(catalog.resolve(applicationId, formId, resourceIds));
  }

  /** Returns authorized field metadata for a selected target. */
  @PostMapping("/describe")
  public Response<FormRelationTargetMetadata> describe(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("formId") String formId,
      @RequestBody FormRelationTarget target) {
    return Response.okay(catalog.describe(applicationId, formId, target));
  }

  /** Source metadata for a related-record view, filtered by source field visibility. */
  @PostMapping("/inverse-source")
  public Response<org.simplepoint.plugin.app.form.api.model.relation.FormInverseSourceMetadata>
      inverseSource(
          @PathVariable("applicationId") String applicationId,
          @PathVariable("formId") String formId,
          @RequestBody FormRelationTarget source) {
    return Response.okay(catalog.inverseSource(applicationId, formId, source));
  }
}
