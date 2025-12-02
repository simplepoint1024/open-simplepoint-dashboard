package org.simplepoint.plugin.i18n.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.i18n.api.entity.Namespace;
import org.simplepoint.plugin.i18n.api.service.I18nNamespaceService;
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
 * I18nNamespaceController is a REST controller that handles HTTP requests related to internationalization
 * namespaces in the SimplePoint application. This controller provides endpoints for retrieving and managing
 * namespace data, facilitating operations such as fetching namespace lists and details.
 */
@RestController
@RequestMapping("/i18n/namespaces")
@Tag(name = "命名空间管理", description = "用于管理系统中的命名空间配置")
public class I18nNamespaceController extends BaseController<I18nNamespaceService, Namespace, String> {

  /**
   * Constructor initializing the controller with a service instance. 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public I18nNamespaceController(I18nNamespaceService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of namespaces based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the namespaces
   *                   用于过滤命名空间的属性映射
   * @param pageable   the pagination and sorting information
   *                   分页和排序信息
   * @return a paginated response containing namespaces that match the given attributes 包含符合给定属性的命名空间的分页响应
   */
  @GetMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('namespaces.view')")
  @Operation(summary = "分页查询命名空间", description = "根据提供的属性和分页参数，检索命名空间的分页列表")
  public Response<Page<Namespace>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Namespace.class);
  }

  /**
   * Adds a new namespace to the system.
   *
   * @param data the namespace data to be added
   *             要添加的命名空间数据
   * @return a response containing the added namespace 包含已添加命名空间的响应
   */
  @PostMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('namespaces.create')")
  @Operation(summary = "添加命名空间", description = "添加一个新的命名空间到系统中")
  public Response<Namespace> add(@RequestBody Namespace data) {
    return ok(service.persist(data));
  }

  /**
   * Modifies an existing namespace in the system.
   *
   * @param data the namespace data to be modified
   *             要修改的命名空间数据
   * @return a response containing the modified namespace 包含已修改命名空间的响应
   */
  @PutMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('namespaces.edit')")
  @Operation(summary = "修改命名空间", description = "修改一个已存在的命名空间信息")
  public Response<Namespace> modify(@RequestBody Namespace data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more namespaces based on the provided namespace ID collection.
   *
   * @param ids a comma-separated string of namespace IDs to be deleted
   *            要删除的命名空间ID的逗号分隔字符串
   * @return a response containing the set of deleted namespace IDs 包含已删除命名空间ID集合的响应
   */
  @DeleteMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('namespaces.delete')")
  @Operation(summary = "删除命名空间", description = "根据提供的命名空间ID集合，删除一个或多个命名空间")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
