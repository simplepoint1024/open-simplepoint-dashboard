package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.entity.FeaturePermissionRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
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
 * FeatureController is a REST controller that handles HTTP requests related to features.
 */
@RestController
@RequestMapping({"/features", "/platform/features"})
@Tag(name = "功能管理", description = "用于管理系统中的功能特性")
public class FeatureController extends BaseController<FeatureService, Feature, String> {

  /**
   * Constructor initializing the controller with a service instance.
   *
   * @param service the service instance
   */
  public FeatureController(FeatureService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of features.
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('features.view')")
  @Operation(summary = "分页查询功能", description = "根据提供的属性和分页参数，检索功能的分页列表")
  public Response<Page<Feature>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Feature.class);
  }

  /**
   * Adds a new feature.
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('features.create')")
  @Operation(summary = "添加功能", description = "添加一个新的功能到系统中")
  public Response<Feature> add(@RequestBody Feature data) {
    return ok(service.create(data));
  }

  /**
   * Modifies an existing feature.
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('features.edit')")
  @Operation(summary = "修改功能", description = "修改一个已存在的功能信息")
  public Response<Feature> modify(@RequestBody Feature data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more features.
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('features.delete')")
  @Operation(summary = "删除功能", description = "根据提供的功能ID集合，删除一个或多个功能")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  @GetMapping("/items")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.config.feature') or hasAuthority('features.config.permission')")
  @Operation(summary = "获取功能候选列表", description = "获取用于应用功能配置的功能候选列表")
  public Response<Page<Feature>> items(Pageable pageable) {
    return ok(service.limit(Map.of(), pageable));
  }

  @GetMapping("/authorized")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('features.config.permission')")
  @Operation(summary = "获取功能已分配权限", description = "获取指定功能已授权的权限标识列表")
  public Response<Collection<String>> authorized(@RequestParam("featureCode") String featureCode) {
    return ok(service.authorizedPermissions(featureCode));
  }

  @PostMapping("/authorize")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('features.config.permission')")
  @Operation(summary = "配置功能权限", description = "为指定功能分配权限标识")
  public Response<Collection<FeaturePermissionRelevance>> authorize(@RequestBody FeaturePermissionsRelevanceDto dto) {
    return ok(service.authorizePermissions(dto));
  }

  @PostMapping("/unauthorized")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('features.config.permission')")
  @Operation(summary = "取消功能权限", description = "取消指定功能已分配的权限标识")
  public Response<Void> unauthorized(@RequestBody FeaturePermissionsRelevanceDto dto) {
    service.unauthorizedPermissions(dto.getFeatureCode(), dto.getPermissionAuthority());
    return Response.okay();
  }
}
