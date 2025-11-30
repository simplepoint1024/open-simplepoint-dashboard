package org.simplepoint.plugin.i18n.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.i18n.api.entity.Region;
import org.simplepoint.plugin.i18n.api.service.I18nRegionService;
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
 * I18nRegionsController is a REST controller that handles HTTP requests related to region
 * information in the internationalization (i18n) module of the SimplePoint application.
 * This controller will provide endpoints for retrieving and managing region data,
 * facilitating operations such as fetching region lists, details, and other related functionalities.
 */
@RestController
@RequestMapping("/i18n/regions")
@Tag(name = "区域管理", description = "用于管理系统中的区域配置")
public class I18nRegionsController extends BaseController<I18nRegionService, Region, String> {
  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public I18nRegionsController(I18nRegionService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of regions based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the regions
   *                   用于过滤区域的属性映射
   * @param pageable   the pagination and sorting information
   *                   分页和排序信息
   * @return a paginated response containing regions that match the given attributes 包含符合给定属性的区域的分页响应
   */
  @GetMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('regions.view')")
  @Operation(summary = "分页查询区域", description = "根据提供的属性和分页参数，检索区域的分页列表")
  public Response<Page<Region>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Region.class);
  }

  /**
   * Adds a new region to the system.
   *
   * @param data the region data to be added
   *             要添加的区域数据
   * @return a response containing the added region 包含已添加区域的响应
   */
  @PostMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('regions.create')")
  @Operation(summary = "添加区域", description = "添加一个新的区域到系统中")
  public Response<Region> add(@RequestBody Region data) {
    return ok(service.add(data));
  }

  /**
   * Modifies an existing region in the system.
   *
   * @param data the region data to be modified
   *             要修改的区域数据
   * @return a response containing the modified region 包含已修改区域的响应
   */
  @PutMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('regions.edit')")
  @Operation(summary = "修改区域", description = "修改一个已存在的区域信息")
  public Response<Region> modify(@RequestBody Region data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more regions based on the provided region IDs.
   *
   * @param ids a comma-separated string of region IDs to be deleted
   *            要删除的区域ID的逗号分隔字符串
   * @return a response containing the set of deleted region IDs 包含已删除区域ID集合的响应
   */
  @DeleteMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('regions.delete')")
  @Operation(summary = "删除区域", description = "根据提供的区域ID集合，删除一个或多个区域")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
