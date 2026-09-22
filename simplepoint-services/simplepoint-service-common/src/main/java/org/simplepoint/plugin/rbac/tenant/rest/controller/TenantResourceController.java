package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Workspace-owned resource settings. Tenant is always taken from authorization context. */
@RestController
@RequestMapping("/tenants/current-resources")
@PreAuthorize("isAuthenticated()")
@Tag(name = "租户资源管理", description = "当前组织工作空间的独立资源目录与平台授权同步")
public class TenantResourceController {
  private final TenantResourceCatalog catalog;

  public TenantResourceController(TenantResourceCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  @Operation(summary = "分页查询当前租户资源")
  public Response<Page<TenantResource>> list(@RequestParam(required = false) String keyword,
      @RequestParam(required = false) String parentId,
      @RequestParam(defaultValue = "false") boolean rootsOnly, Pageable pageable) {
    return Response.okay(catalog.list(keyword, parentId, rootsOnly, pageable));
  }

  @PutMapping("/mode")
  @Operation(summary = "切换当前组织租户的资源加载类型")
  public Response<TenantResourceMode> selectMode(@RequestBody ModeCommand command) {
    return Response.okay(catalog.selectMode(command.mode()));
  }

  @PostMapping("/sync")
  @Operation(summary = "从当前平台授权同步租户资源")
  public Response<TenantResourceCatalog.SyncResult> sync(@RequestParam(defaultValue = "false") boolean overwrite) {
    return Response.okay(catalog.synchronize(overwrite));
  }

  @GetMapping("/sync/preview")
  @Operation(summary = "预览平台授权资源同步变化")
  public Response<TenantResourceCatalog.SyncPreview> previewSync(
      @RequestParam(defaultValue = "false") boolean overwrite) {
    return Response.okay(catalog.previewSynchronization(overwrite));
  }

  @GetMapping("/{id}")
  @Operation(summary = "查询当前租户资源详情")
  public Response<TenantResource> get(@PathVariable String id) {
    return Response.okay(catalog.get(id));
  }

  @PostMapping
  @Operation(summary = "新建租户导航资源")
  public Response<TenantResource> create(@RequestBody TenantResourceCatalog.Mutation mutation) {
    return Response.okay(catalog.create(mutation));
  }

  @PutMapping("/{id}")
  @Operation(summary = "修改当前租户资源")
  public Response<TenantResource> update(@PathVariable String id,
      @RequestBody TenantResourceCatalog.Mutation mutation) {
    return Response.okay(catalog.update(id, mutation));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "删除租户自建资源")
  public Response<Void> delete(@PathVariable String id) {
    catalog.delete(id);
    return Response.okay();
  }

  public record ModeCommand(TenantResourceMode mode) {}
}
