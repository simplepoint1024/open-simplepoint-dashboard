package org.simplepoint.plugin.rbac.tenant.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationFeatureRelevance;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationFeaturesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.service.ApplicationService;
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
 * ApplicationController is a REST controller that handles HTTP requests related to applications.
 */
@RestController
@RequestMapping({"/applications", "/platform/applications"})
@Tag(name = "应用管理", description = "用于管理租户下的应用")
public class ApplicationController extends BaseController<ApplicationService, Application, String> {

  /**
   * Constructs an ApplicationController with the specified ApplicationService.
   *
   * @param service the service to be used by this controller
   */
  public ApplicationController(ApplicationService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of applications based on provided attributes and pagination parameters.
   *
   * @param attributes a map of attributes to filter the applications
   * @param pageable pagination parameters for the query
   * @return a paginated list of applications matching the criteria
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.view')")
  @Operation(summary = "分页查询应用", description = "根据提供的属性和分页参数，检索应用的分页列表")
  public Response<Page<Application>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Application.class);
  }

  /**
   * Adds a new application to the system.
   *
   * @param data the application data to be added
   * @return the created application
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.create')")
  @Operation(summary = "添加应用", description = "添加一个新的应用到系统中")
  public Response<Application> add(@RequestBody Application data) {
    return ok(service.create(data));
  }

  /**
   * Modifies an existing application based on the provided data.
   *
   * @param data the application data with updated information
   * @return the modified application
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.edit')")
  @Operation(summary = "修改应用", description = "修改一个已存在的应用信息")
  public Response<Application> modify(@RequestBody Application data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more applications based on the provided application IDs.
   *
   * @param ids a comma-separated string of application IDs to be deleted
   * @return a set of deleted application IDs
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.delete')")
  @Operation(summary = "删除应用", description = "根据提供的应用ID集合，删除一个或多个应用")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }

  @GetMapping("/items")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('packages.config.application') or hasAuthority('applications.config.feature')")
  @Operation(summary = "获取应用候选列表", description = "获取用于套餐应用配置的应用候选列表")
  public Response<Page<Application>> items(Pageable pageable) {
    return ok(service.limit(Map.of(), pageable));
  }

  @GetMapping("/authorized")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.config.feature')")
  @Operation(summary = "获取应用已分配功能", description = "获取指定应用已授权的功能编码列表")
  public Response<Collection<String>> authorized(@RequestParam("applicationCode") String applicationCode) {
    return ok(service.authorizedFeatures(applicationCode));
  }

  @PostMapping("/authorize")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.config.feature')")
  @Operation(summary = "配置应用功能", description = "为指定应用分配功能编码")
  public Response<Collection<ApplicationFeatureRelevance>> authorize(@RequestBody ApplicationFeaturesRelevanceDto dto) {
    return ok(service.authorizeFeatures(dto));
  }

  @PostMapping("/unauthorized")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('applications.config.feature')")
  @Operation(summary = "取消应用功能", description = "取消指定应用已分配的功能编码")
  public Response<Void> unauthorized(@RequestBody ApplicationFeaturesRelevanceDto dto) {
    service.unauthorizedFeatures(dto.getApplicationCode(), dto.getFeatureCodes());
    return Response.okay();
  }
}
