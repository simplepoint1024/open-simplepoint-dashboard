package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.tenant.api.entity.Package;
import org.simplepoint.plugin.rbac.tenant.api.service.PackageService;
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
 * PackageController is a REST controller that handles HTTP requests related to packages.
 */
@RestController
@RequestMapping("/packages")
@Tag(name = "套餐包管理", description = "用于管理租户下的套餐包")
public class PackageController extends BaseController<PackageService, Package, String> {

  /**
   * Constructor initializing the controller with a service instance.
   *
   * @param service the service instance
   */
  public PackageController(PackageService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of packages.
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('packages.view')")
  @Operation(summary = "分页查询套餐包", description = "根据提供的属性和分页参数，检索套餐包的分页列表")
  public Response<Page<Package>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Package.class);
  }

  /**
   * Adds a new package.
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('packages.create')")
  @Operation(summary = "添加套餐包", description = "添加一个新的套餐包到系统中")
  public Response<Package> add(@RequestBody Package data) {
    return ok(service.create(data));
  }

  /**
   * Modifies an existing package.
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('packages.edit')")
  @Operation(summary = "修改套餐包", description = "修改一个已存在的套餐包信息")
  public Response<Package> modify(@RequestBody Package data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more packages.
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('packages.delete')")
  @Operation(summary = "删除套餐包", description = "根据提供的套餐包ID集合，删除一个或多个套餐包")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
