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

  public OrganizationController(OrganizationService service) {
    super(service);
  }

  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.view')")
  @Operation(summary = "分页查询组织机构", description = "根据提供的属性和分页参数，检索组织机构的分页列表")
  public Response<Page<Organization>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Organization.class);
  }

  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.create')")
  @Operation(summary = "添加组织机构", description = "添加一个新的组织机构到当前租户下")
  public Response<Organization> add(@RequestBody Organization data) {
    return ok(service.create(data));
  }

  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.edit')")
  @Operation(summary = "修改组织机构", description = "修改一个已存在的组织机构信息")
  public Response<Organization> modify(@RequestBody Organization data) {
    return ok(service.modifyById(data));
  }

  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.delete')")
  @Operation(summary = "删除组织机构", description = "根据提供的组织机构ID集合，删除一个或多个组织机构")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
