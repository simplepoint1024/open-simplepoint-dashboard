package org.simplepoint.app.form.controller;

import java.util.Locale;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormCatalogPage;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDesignerContract;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCatalogService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform table and designer discovery endpoints; mutations stay in the form controller. */
@RestController
@RequestMapping("/applications/{applicationId}/forms")
@PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
public class ApplicationFormCatalogController {

  private final ApplicationFormCatalogService catalog;

  /** Creates the transport over the tenant-aware form directory. */
  public ApplicationFormCatalogController(final ApplicationFormCatalogService catalog) {
    this.catalog = catalog;
  }

  /** Returns one server-filtered and deterministically sorted table page. */
  @GetMapping("/catalog")
  public Response<ApplicationFormCatalogPage> page(
      @PathVariable("applicationId") final String applicationId,
      @RequestParam final Map<String, String> filters,
      @PageableDefault(size = 20) final Pageable pageable
  ) {
    return Response.okay(catalog.page(applicationId, filters,
        pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().stream()
            .map(order -> order.getProperty() + ","
                + order.getDirection().name().toLowerCase(Locale.ROOT)).toList()));
  }

  /** Returns the platform schema and permission-filtered directory actions. */
  @GetMapping("/catalog/schema")
  public Response<Map<String, Object>> schema(
      @PathVariable("applicationId") final String applicationId
  ) {
    return Response.okay(catalog.schema(applicationId));
  }

  /** Returns versioned component descriptors and server-owned designer capabilities. */
  @GetMapping("/designer-contract")
  public Response<ApplicationFormDesignerContract> designer(
      @PathVariable("applicationId") final String applicationId
  ) {
    return Response.okay(catalog.designer(applicationId));
  }
}
