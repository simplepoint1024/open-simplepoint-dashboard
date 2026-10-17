package org.simplepoint.app.catalog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.ApplicationDetail;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.ApplicationSummary;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.CreateApplication;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.CreatePage;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.PageSummary;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.Permissions;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.PublicationPage;
import org.simplepoint.core.http.Response;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Application workbench metadata endpoints; all lookups are tenant-scoped. */
@RestController
@RequestMapping("/applications")
@PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
@Tag(name = "应用工作台", description = "租户应用及页面目录")
public class WorkspaceApplicationController {
  private final WorkspaceApplicationCatalog catalog;

  /** Creates the workspace application controller. */
  public WorkspaceApplicationController(WorkspaceApplicationCatalog catalog) {
    this.catalog = catalog;
  }

  /** Returns actual creation capabilities for this workspace. */
  @GetMapping("/permissions")
  @Operation(summary = "查询当前用户的应用工作台操作权限")
  public Response<Permissions> permissions() {
    return Response.okay(catalog.permissions());
  }

  /** Queries application metadata before database pagination. */
  @GetMapping
  @Operation(summary = "分页查询租户应用")
  public Response<Page<ApplicationSummary>> list(
      @RequestParam Map<String, String> parameters, Pageable page) {
    return Response.okay(catalog.list(parameters, page));
  }

  /** Returns the authorized application query schema. */
  @GetMapping("/schema")
  public Response<Map<String, Object>> schema() {
    return Response.okay(catalog.schema());
  }

  /** Loads an application and its page directory. */
  @GetMapping("/{id}")
  @Operation(summary = "查询应用和页面目录")
  public Response<ApplicationDetail> detail(@PathVariable("id") String id) {
    return Response.okay(catalog.detail(id));
  }

  /** Creates an application and its main page atomically. */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.apps.create')")
  @Operation(summary = "创建应用及其主页面")
  public Response<ApplicationDetail> create(@RequestBody CreateApplication command) {
    return Response.okay(catalog.create(command));
  }

  /** Returns bounded tenant-owned publication targets for the platform entity selector. */
  @GetMapping("/{id}/publication-pages")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.publish')")
  @Operation(summary = "分页查询表单发布目标页面")
  public Response<Page<PublicationPage>> publicationPages(
      @PathVariable("id") String id,
      @RequestParam Map<String, String> parameters, Pageable page) {
    return Response.okay(catalog.publicationPages(id, parameters, page));
  }

  /** Adds a page under the captured application and tenant. */
  @PostMapping("/{id}/pages")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.pages.create')")
  @Operation(summary = "添加附属页面")
  public Response<PageSummary> addPage(
      @PathVariable("id") String id, @RequestBody CreatePage command) {
    return Response.okay(catalog.addPage(id, command));
  }
}
