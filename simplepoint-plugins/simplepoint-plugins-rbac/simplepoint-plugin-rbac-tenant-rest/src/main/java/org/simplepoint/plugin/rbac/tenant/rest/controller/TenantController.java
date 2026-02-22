package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantService;
import org.simplepoint.plugin.rbac.tenant.api.vo.NamedTenantVo;
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
 * TenantController is a REST controller that handles HTTP requests related to tenants.
 */
@RestController
@RequestMapping("/tenants")
@Tag(name = "租户管理", description = "用于管理系统中的租户")
public class TenantController extends BaseController<TenantService, Tenant, String> {

  /**
   * Constructor initializing the controller with a service instance.
   *
   * @param service the service instance
   */
  public TenantController(TenantService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of tenants.
   *
   * @param attributes attributes map
   * @param pageable   pageable
   * @return page
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('tenants.view')")
  @Operation(summary = "分页查询租户", description = "根据提供的属性和分页参数，检索租户的分页列表")
  public Response<Page<Tenant>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Tenant.class);
  }

  /**
   * Adds a new tenant.
   *
   * @param data tenant
   * @return created tenant
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('tenants.create')")
  @Operation(summary = "添加租户", description = "添加一个新的租户到系统中")
  public Response<Tenant> add(@RequestBody Tenant data) {
    return ok(service.create(data));
  }

  /**
   * Modifies an existing tenant.
   *
   * @param data tenant
   * @return modified tenant
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('tenants.edit')")
  @Operation(summary = "修改租户", description = "修改一个已存在的租户信息")
  public Response<Tenant> modify(@RequestBody Tenant data) {
    return ok(service.modifyById(data));
  }

  /**
   * Removes tenants by ids.
   *
   * @param ids comma separated ids
   * @return removed ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('tenants.delete')")
  @Operation(summary = "删除租户", description = "根据提供的租户ID集合，删除一个或多个租户")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  @GetMapping("/current")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "获取当前用户的租户", description = "获取当前认证用户所属的租户列表")
  public Response<Set<NamedTenantVo>> getCurrentUserTenants() {
    return ok(service.getCurrentUserTenants());
  }
}
