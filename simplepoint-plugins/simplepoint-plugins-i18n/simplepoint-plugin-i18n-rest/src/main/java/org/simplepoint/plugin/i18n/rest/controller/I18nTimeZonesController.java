package org.simplepoint.plugin.i18n.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.i18n.api.entity.TimeZone;
import org.simplepoint.plugin.i18n.api.service.I18nTimeZoneService;
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
 * I18nTimeZonesController is a REST controller that handles HTTP requests related to time zone
 * information in the internationalization (i18n) module of the SimplePoint application.
 * This controller will provide endpoints for retrieving and managing time zone data,
 * facilitating operations such as fetching time zone lists, details, and other related functionalities.
 */
@RestController
@RequestMapping("/i18n/timezones")
@Tag(name = "时区管理", description = "用于管理系统中的时区配置")
public class I18nTimeZonesController extends BaseController<I18nTimeZoneService, TimeZone, String> {

  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public I18nTimeZonesController(I18nTimeZoneService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of time zones based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the time zones
   *                   用于过滤时区的属性映射
   * @param pageable   the pagination and sorting information
   *                   分页和排序信息
   * @return a paginated response containing time zones that match the given attributes 包含符合给定属性的时区的分页响应
   */
  @GetMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('timezones.view')")
  @Operation(summary = "分页查询时区", description = "根据提供的属性和分页参数，检索时区的分页列表")
  public Response<Page<TimeZone>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), TimeZone.class);
  }

  /**
   * Adds a new time zone to the system.
   *
   * @param data the time zone data to be added
   *             要添加的时区数据
   * @return a response containing the added time zone 包含已添加时区的响应
   * @throws Exception if an error occurs during addition
   *                   如果添加过程中发生错误
   */
  @PostMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('timezones.create')")
  @Operation(summary = "添加时区", description = "添加一个新的时区到系统中")
  public Response<TimeZone> add(@RequestBody TimeZone data) throws Exception {
    return ok(service.persist(data));
  }

  /**
   * Modifies an existing time zone in the system.
   *
   * @param data the time zone data to be modified
   *             要修改的时区数据
   * @return a response containing the modified time zone 包含已修改时区的响应
   */
  @PutMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('timezones.edit')")
  @Operation(summary = "修改时区", description = "修改一个已存在的时区信息")
  public Response<TimeZone> modify(@RequestBody TimeZone data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more time zones based on the provided time zone ID collection.
   *
   * @param ids a comma-separated string of time zone IDs to be deleted
   *            要删除的时区ID的逗号分隔字符串
   * @return a response containing the set of deleted time zone IDs 包含已删除时区ID集合的响应
   */
  @DeleteMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('timezones.delete')")
  @Operation(summary = "删除时区", description = "根据提供的时区ID集合，删除一个或多个时区")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
