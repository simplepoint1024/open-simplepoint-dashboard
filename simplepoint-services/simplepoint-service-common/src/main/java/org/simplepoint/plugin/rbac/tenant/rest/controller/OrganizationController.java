package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.service.OrganizationService;
import org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationOptionPage;
import org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationOptionVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization controller.
 */
@RestController
@RequestMapping({"/organizations", "/platform/organizations"})
@Tag(name = "组织机构管理", description = "用于管理当前租户下的组织机构")
public class OrganizationController extends BaseController<OrganizationService, Organization, String> {

  /**
   * Organization Controller.
   */
  public OrganizationController(OrganizationService service) {
    super(service);
  }

  /** Reads one draft selection side; POST carries bounded draft IDs and never changes permissions. */
  @PostMapping("/selection")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.view')")
  public Response<Page<Map<String, Object>>> selection(
      @RequestBody org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationSelection draft, Pageable pageable) {
    return ok(service.selection(draft, pageable).map(org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationSelectionRow::project));
  }

  /** Uses the same tenant and field permissions as the read-only draft query. */
  @GetMapping("/selection/schema")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.view')")
  public Response<Object> selectionSchema() {
    return ok(service.selectionSchema());
  }

  /**
   * @ Get Mapping.
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.view')")
  @Operation(summary = "分页查询组织机构", description = "根据提供的属性和分页参数，检索组织机构的分页列表")
  public Response<Page<Organization>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Organization.class);
  }

  /** Loads a counted tree level without treating a selector slice as a full page. */
  @GetMapping("/tree")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.view')")
  public Response<Page<Map<String, Object>>> tree(
      @RequestParam Map<String, String> attributes, Pageable pageable) {
    Map<String, String> filters = new java.util.LinkedHashMap<>(attributes);
    String parent = filters.remove("treeParentId");
    return ok(service.tree(parent, filters, pageable).map(this::projectTreeRow));
  }

  private Map<String, Object> projectTreeRow(OrganizationOptionVo row) {
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("id", row.id());
    fields.put("name", row.name());
    fields.put("code", row.code());
    fields.put("parentId", row.parentId());
    fields.put("type", row.type());
    fields.put("description", row.description());
    fields.put("sort", row.sort());
    fields.put("enabled", row.enabled());
    fields.put("hasChildren", row.hasChildren());
    return org.simplepoint.core.jackson.FieldPermissionPolicy.project(Organization.class, fields);
  }

  /**
   * Loads organizations for the shared lazy organization selector.
   */
  @GetMapping("/options")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.view')")
  @Operation(summary = "查询组织选择项", description = "按层级或关键词分页查询轻量组织选择项，不执行全量统计")
  public Response<Map<String, Object>> options(
      @RequestParam(value = "keyword", required = false) String keyword,
      @RequestParam(value = "parentId", required = false) String parentId,
      @RequestParam(value = "ids", required = false) String ids,
      @RequestParam(value = "excludeId", required = false) String excludeId,
      @RequestParam(value = "flat", defaultValue = "false") boolean flat,
      Pageable pageable
  ) {
    OrganizationOptionPage page = service.options(keyword, parentId, ids, excludeId, flat, pageable);
    return ok(Map.of("content", page.content().stream().map(OrganizationOptionVo::project).toList(),
        "number", page.number(), "size", page.size(), "hasNext", page.hasNext()));
  }

  /**
   * @ Post Mapping.
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.create')")
  @Operation(summary = "添加组织机构", description = "添加一个新的组织机构到当前租户下")
  public Response<Organization> add(@RequestBody Organization data) {
    return ok(service.create(data));
  }

  /**
   * @ Put Mapping.
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.edit')")
  @Operation(summary = "修改组织机构", description = "修改一个已存在的组织机构信息")
  public Response<Organization> modify(@RequestBody Organization data) {
    return ok(service.modifyById(data));
  }

  /**
   * @ Delete Mapping.
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.delete')")
  @Operation(summary = "删除组织机构", description = "根据提供的组织机构ID集合，删除一个或多个组织机构")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
