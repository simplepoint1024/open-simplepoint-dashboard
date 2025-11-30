package org.simplepoint.plugin.i18n.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.i18n.api.entity.Language;
import org.simplepoint.plugin.i18n.api.service.I18nLanguageService;
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
 * I18nLanguagesController is a REST controller that handles HTTP requests related to language
 * information in the internationalization (i18n) module of the SimplePoint application.
 * This controller will provide endpoints for retrieving and managing language data,
 * facilitating operations such as fetching language lists, details, and other related functionalities.
 */
@RestController
@RequestMapping("/i18n/languages")
@Tag(name = "语言管理", description = "用于管理系统中的语言配置")
public class I18nLanguagesController extends BaseController<I18nLanguageService, Language, String> {

  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public I18nLanguagesController(I18nLanguageService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of Language based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the Language
   *                   用于过滤语言的属性映射
   * @param pageable   the pagination and sorting information
   *                   分页和排序信息
   * @return a paginated response containing Language that match the given attributes 包含符合给定属性的语言的分页响应
   */
  @GetMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('languages.view')")
  @Operation(summary = "分页查询语言", description = "根据提供的属性和分页参数，检索语言的分页列表")
  public Response<Page<Language>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Language.class);
  }

  /**
   * Retrieves the global language mapping based on the provided locale and namespace.
   *
   * @return a response containing the global language mapping 包含全局语言映射的响应
   */
  @GetMapping("/mapping")
  @Operation(summary = "获取全局语言映射", description = "根据提供的语言环境和命名空间，获取全局语言映射")
  public Response<Map<String, String>> mapping() {
    return ok(service.mapping());
  }

  /**
   * Adds a new country to the system.
   *
   * @param data the country data to be added
   *             要添加的语言数据
   * @return a response containing the added country 包含已添加语言的响应
   * @throws Exception if an error occurs during the addition
   *                   如果添加过程中发生错误
   */
  @PostMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('languages.create')")
  @Operation(summary = "添加语言", description = "添加一个新的语言到系统中")
  public Response<Language> add(@RequestBody Language data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Modifies an existing country's information.
   *
   * @param data the country data to be modified
   *             要修改的语言数据
   * @return a response containing the modified country 包含已修改语言的响应
   */
  @PutMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('languages.edit')")
  @Operation(summary = "修改语言", description = "修改一个已存在的语言信息")
  public Response<Language> modify(@RequestBody Language data) {
    return ok(service.modifyById(data));
  }

  /**
   * Removes one or more Language identified by their IDs.
   *
   * @param ids a comma-separated string of country IDs to be deleted
   *            要删除的语言ID的逗号分隔字符串
   * @return a response containing the set of deleted country IDs 包含已删除语言ID集合的响应
   */
  @DeleteMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('languages.delete')")
  @Operation(summary = "删除语言", description = "根据提供的语言ID集合，删除一个或多个语言")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
