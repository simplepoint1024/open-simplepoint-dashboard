package org.simplepoint.app.form.controller;

import java.util.Map;
import org.simplepoint.app.form.service.ApplicationFormReleaseCatalog;
import org.simplepoint.app.form.service.ApplicationFormReleaseCatalog.ReleaseRow;
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

/** Thin standard table transport for durable form publication history. */
@RestController
@RequestMapping("/applications/{applicationId}/forms/{formId}/releases")
@PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
public class ApplicationFormReleaseController {
  private final ApplicationFormReleaseCatalog catalog;

  /** Creates the publication read transport. */
  public ApplicationFormReleaseController(final ApplicationFormReleaseCatalog catalog) {
    this.catalog = catalog;
  }

  /** Lists tenant- and form-scoped saved operations. */
  @GetMapping
  public Response<Page<ReleaseRow>> page(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable
  ) {
    return Response.okay(catalog.page(applicationId, formId, filters, pageable));
  }

  /** Provides platform fields, filters, sort contracts and permission-filtered actions. */
  @GetMapping("/schema")
  public Response<Map<String, Object>> schema(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId
  ) {
    return Response.okay(catalog.schema(applicationId, formId));
  }
}
