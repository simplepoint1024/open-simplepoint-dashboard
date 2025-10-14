package org.simplepoint.plugin.rbac.menu.rest.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.Map;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.security.entity.RemoteModule;
import org.simplepoint.security.service.RemoteModuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/modules")
@Tag(name = "远程模块管理", description = "用于管理系统中的远程模块")
public class RemoteModuleController
    extends BaseController<RemoteModuleService, RemoteModule, String> {

  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public RemoteModuleController(final @Autowired(required = false) RemoteModuleService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of remote modules based on the specified filter attributes.
   *
   * @param attributes a map of filtering attributes
   * @param pageable   pagination information
   * @return a paginated list of remote module records wrapped in {@link Response}
   * @throws Exception if an error occurs during retrieval
   */
  @GetMapping
  @Operation(summary = "分页查询远程模块", description = "根据提供的属性和分页参数，检索远程模块的分页列表")
  public Response<Page<RemoteModule>> limit(
      @RequestParam Map<String, String> attributes,
      Pageable pageable
  )
      throws Exception {

    var list = new ArrayList<RemoteModule>();
    RemoteModule remoteModule = new RemoteModule();
    remoteModule.setName("common");
    remoteModule.setEntry("http://127.0.0.1:8080/common/mf/mf-manifest.json");
    list.add(remoteModule);
    return limit(new PageImpl<>(list), RemoteModule.class);
  }
}
