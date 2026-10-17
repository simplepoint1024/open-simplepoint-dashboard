package org.simplepoint.app.form.controller;

import java.util.List;
import org.simplepoint.app.form.service.ApplicationFormWorkflowCatalog;
import org.simplepoint.app.form.service.ApplicationFormWorkflowCatalog.Choice;
import org.simplepoint.app.form.service.ApplicationFormWorkflowCatalog.Detail;
import org.simplepoint.app.form.service.ApplicationFormWorkflowCatalog.RuntimePage;
import org.simplepoint.core.http.Response;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Registered form-provider metadata, separated from business record data. */
@RestController
@RequestMapping("/workflow/forms")
public class ApplicationFormWorkflowCatalogController {
  private final ApplicationFormWorkflowCatalog catalog;

  public ApplicationFormWorkflowCatalogController(ApplicationFormWorkflowCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
  public Response<Page<Choice>> forms(
      @RequestParam(defaultValue = "", name = "keyword") String keyword,
      @RequestParam(defaultValue = "0", name = "page") int page,
      @RequestParam(defaultValue = "20", name = "size") int size) {
    return Response.okay(catalog.forms(keyword, page, size));
  }

  @GetMapping("/options")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
  public Response<List<Choice>> options(@RequestParam(name = "ids") List<String> ids) {
    return Response.okay(catalog.options(ids));
  }

  @GetMapping("/{identity}")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
  public Response<Detail> detail(@PathVariable(name = "identity") String identity) {
    return Response.okay(catalog.detail(identity));
  }

  @GetMapping("/{formId}/versions/{version}/pages")
  @PreAuthorize("isAuthenticated()")
  public Response<Page<RuntimePage>> pages(
      @PathVariable(name = "formId") String formId,
      @PathVariable(name = "version") int version,
      @RequestParam(defaultValue = "", name = "keyword") String keyword,
      @RequestParam(defaultValue = "0", name = "page") int page,
      @RequestParam(defaultValue = "20", name = "size") int size) {
    return Response.okay(catalog.pages(formId, version, keyword, page, size));
  }
}
