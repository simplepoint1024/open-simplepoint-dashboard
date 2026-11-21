package org.simplepoint.app.catalog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.ApplicationDetail;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.ApplicationSummary;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.CatalogRevision;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.CreateApplication;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.CreatePage;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.PageSummary;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.Permissions;
import org.simplepoint.app.catalog.service.WorkspaceApplicationCatalog.PublicationPage;
import org.simplepoint.app.catalog.service.WorkspaceApplicationRemoval;
import org.simplepoint.app.catalog.service.WorkspaceApplicationRemoval.Progress;
import org.simplepoint.app.catalog.service.WorkspaceApplicationRemoval.Selection;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
  private static final String CAN_DELETE =
      "hasRole('Administrator') or (hasAuthority('app.workbench.view') and hasAuthority('app.workbench.apps.delete'))";
  private final WorkspaceApplicationCatalog catalog;
  private final ObjectProvider<WorkspaceApplicationRemoval> removal;

  /** Creates the workspace application controller. */
  public WorkspaceApplicationController(WorkspaceApplicationCatalog catalog,
      ObjectProvider<WorkspaceApplicationRemoval> removal) {
    this.catalog = catalog;
    this.removal = removal;
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

  @DeleteMapping("/{id}")
  @PreAuthorize(CAN_DELETE)
  @Operation(summary = "删除未发布的应用草稿")
  public org.springframework.http.ResponseEntity<Void> delete(
      @PathVariable("id") String id, @RequestParam("expectedUpdatedAt") Instant expectedUpdatedAt) {
    catalog.deleteApplication(id, expectedUpdatedAt);
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  @DeleteMapping
  @PreAuthorize(CAN_DELETE)
  @Operation(summary = "批量删除应用草稿")
  public org.springframework.http.ResponseEntity<Void> deleteApplications(@RequestBody List<CatalogRevision> selection) {
    catalog.deleteApplications(selection);
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  @GetMapping("/removals/capability")
  @PreAuthorize(CAN_DELETE)
  public Response<Map<String, Boolean>> removalCapability() {
    return Response.okay(Map.of("enabled", removal.getIfAvailable() != null));
  }

  @PostMapping("/removals")
  @PreAuthorize(CAN_DELETE)
  public Response<List<Progress>> removeApplications(@RequestBody List<Selection> selection) {
    return Response.okay(requireRemoval().request(selection));
  }

  @GetMapping("/removals")
  @PreAuthorize(CAN_DELETE)
  public Response<List<Progress>> removalProgress(@RequestParam("applicationIds") List<String> applicationIds) {
    return Response.okay(requireRemoval().progress(applicationIds));
  }

  private WorkspaceApplicationRemoval requireRemoval() {
    var available = removal.getIfAvailable();
    if (available == null) throw new ApplicationFormException(ApplicationFormErrorCode.APPLICATION_DELETE_BLOCKED,
        "Offline application removal is unavailable");
    return available;
  }

  @DeleteMapping("/{id}/pages/{pageId}")
  @PreAuthorize("hasRole('Administrator') or (hasAuthority('app.workbench.view') and hasAuthority('app.workbench.pages.delete'))")
  @Operation(summary = "删除未发布的附属页面")
  public org.springframework.http.ResponseEntity<Void> deletePage(
      @PathVariable("id") String id, @PathVariable("pageId") String pageId,
      @RequestParam("expectedUpdatedAt") Instant expectedUpdatedAt) {
    catalog.deletePage(id, pageId, expectedUpdatedAt);
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/pages")
  @PreAuthorize("hasRole('Administrator') or (hasAuthority('app.workbench.view') and hasAuthority('app.workbench.pages.delete'))")
  @Operation(summary = "批量删除未发布的附属页面")
  public org.springframework.http.ResponseEntity<Void> deletePages(
      @PathVariable("id") String id, @RequestBody List<CatalogRevision> selection) {
    catalog.deletePages(id, selection);
    return org.springframework.http.ResponseEntity.noContent().build();
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
      @PathVariable("id") String id, @RequestParam Map<String, String> parameters, Pageable page) {
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
