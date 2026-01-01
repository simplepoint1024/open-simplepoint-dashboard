package org.simplepoint.plugin.rbac.menu.rest.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.menu.api.vo.MicroModuleItemVo;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.plugin.rbac.menu.api.service.MicroAppService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing remote module entities.
 *
 * <p>This controller provides operations to retrieve and manage remote modules.
 * </p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@RestController
@RequestMapping("/ops/microapps")
@Tag(name = "远程模块管理", description = "用于管理系统中的远程模块")
public class MicroAppController
    extends BaseController<MicroAppService, MicroModule, String> {

  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public MicroAppController(final @Autowired(required = false) MicroAppService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of remote modules based on the specified filter attributes.
   *
   * @param attributes a map of filtering attributes
   * @param pageable   pagination information
   * @return a paginated list of remote module records wrapped in {@link Response}
   */
  @GetMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('microapp.view')")
  @Operation(summary = "分页查询远程模块", description = "根据提供的属性和分页参数，检索远程模块的分页列表")
  public Response<Page<MicroModule>> limit(
      @RequestParam Map<String, String> attributes,
      Pageable pageable
  ) {
//    var list = new ArrayList<MicroModuleItemVo>();
//    list.add(new MicroModuleItemVo("common", "http://127.0.0.1:8080/common/mf/mf-manifest.json"));
    return limit(service.limit(attributes, pageable), MicroModule.class);
  }

  /**
   * Loads the list of remote modules configured in the system.
   *
   * @return a set of {@link MicroModuleItemVo} representing the loaded remote modules wrapped in {@link Response}
   */
  @GetMapping("/loader")
  @Operation(summary = "加载远程模块列表", description = "加载系统中配置的远程模块列表")
  public Response<Set<MicroModuleItemVo>> loadApps() {
    Set<MicroModuleItemVo> apps = service.loadApps();
    return ok(apps);
  }
}
