package org.simplepoint.plugin.i18n.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.i18n.api.entity.Countries;
import org.simplepoint.plugin.i18n.api.service.I18nCountriesService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I18nCountriesController is a REST controller that handles HTTP requests related to country
 * information in the internationalization (i18n) module of the SimplePoint application.
 * This controller will provide endpoints for retrieving and managing country data,
 * facilitating operations such as fetching country lists, details, and other related functionalities.
 */
@RestController
@RequestMapping("/i18n/country")
@Tag(name = "国家管理", description = "用于管理系统中的国家配置")
public class I18nCountriesController extends BaseController<I18nCountriesService, Countries, String> {
  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public I18nCountriesController(I18nCountriesService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of countries based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the countries
   *                   用于过滤国家的属性映射
   * @param pageable   the pagination and sorting information
   *                   分页和排序信息
   * @return a paginated response containing countries that match the given attributes
   *         包含符合给定属性的国家的分页响应
   * @throws Exception if an error occurs during retrieval
   *                   如果检索过程中发生错误
   */
  @GetMapping
  @Operation(summary = "分页查询国家", description = "根据提供的属性和分页参数，检索国家的分页列表")
  public Response<Page<Countries>> limit(@RequestParam Map<String, String> attributes, Pageable pageable)
      throws Exception {
    return limit(service.limit(attributes, pageable), Countries.class);
  }

  /**
   * Adds a new country to the system.
   *
   * @param data the country data to be added
   *             要添加的国家数据
   * @return a response containing the added country
   *         包含已添加国家的响应
   * @throws Exception if an error occurs during the addition
   *                   如果添加过程中发生错误
   */
  @PostMapping
  @Operation(summary = "添加国家", description = "添加一个新的国家到系统中")
  public Response<Countries> add(@RequestBody Countries data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Modifies an existing country's information.
   *
   * @param data the country data to be modified
   *             要修改的国家数据
   * @return a response containing the modified country
   *         包含已修改国家的响应
   * @throws Exception if an error occurs during the modification
   *                   如果修改过程中发生错误
   */
  @PutMapping
  @Operation(summary = "修改国家", description = "修改一个已存在的国家信息")
  public Response<Countries> modify(@RequestBody Countries data) throws Exception {
    return ok(service.modifyById(data));
  }

  /**
   * Removes one or more countries identified by their IDs.
   *
   * @param ids a comma-separated string of country IDs to be deleted
   *            要删除的国家ID的逗号分隔字符串
   * @return a response containing the set of deleted country IDs
   *         包含已删除国家ID集合的响应
   * @throws Exception if an error occurs during deletion
   *                   如果删除过程中发生错误
   */
  @DeleteMapping
  @Operation(summary = "删除国家", description = "根据提供的国家ID集合，删除一个或多个国家")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) throws Exception {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
