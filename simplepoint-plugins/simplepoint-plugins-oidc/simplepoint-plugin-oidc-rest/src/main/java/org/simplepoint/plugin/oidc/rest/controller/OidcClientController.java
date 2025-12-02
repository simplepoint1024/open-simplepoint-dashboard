package org.simplepoint.plugin.oidc.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.api.service.OidcClientService;
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
 * OidcClientController handles HTTP requests related to OIDC Client entities.
 *
 * <p>This controller extends the BaseController to provide CRUD operations for OIDC Client entities.
 * It uses the OidcClientService to perform business logic and interact with the data layer.
 * </p>
 */
@RestController
@RequestMapping("/oidc/clients")
public class OidcClientController extends BaseController<OidcClientService, Client, String> {
  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public OidcClientController(OidcClientService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of Clients based on the specified filter attributes.
   *
   * @param attributes a map of filtering attributes
   * @param pageable   pagination information
   * @return a paginated list of Client records wrapped in {@link Response}
   * @throws Exception if an error occurs during retrieval
   */
  @GetMapping
  @Operation(summary = "分页查询客户端", description = "根据提供的属性和分页参数，检索客户端的分页列表")
  public Response<Page<Client>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) throws Exception {
    return limit(service.limit(attributes, pageable), Client.class);
  }

  /**
   * Adds a new Client record.
   *
   * @param data the {@link Client} instance to be added
   * @return the added Client record wrapped in {@link Response}
   * @throws Exception if an error occurs during creation
   */
  @PostMapping
  @Operation(summary = "添加新客户端", description = "将新的客户端添加到系统中")
  public Response<Client> add(@RequestBody Client data) throws Exception {
    return ok(service.persist(data));
  }

  /**
   * Modifies an existing Client record.
   *
   * @param data the {@link Client} instance to be updated
   * @return the updated Client record wrapped in {@link Response}
   * @throws Exception if an error occurs during modification
   */
  @PutMapping
  @Operation(summary = "更新客户端信息", description = "更新系统中现有客户端的信息")
  public Response<Client> modify(@RequestBody Client data) throws Exception {
    return ok(service.modifyById(data));
  }

  /**
   * Removes Client records by their IDs.
   *
   * @param ids a comma-separated string of Client IDs to be removed
   * @return a success response indicating removal completion
   * @throws Exception if an error occurs during deletion
   */
  @DeleteMapping
  @Operation(summary = "删除客户端", description = "根据提供的客户端ID列表，删除对应的客户端记录")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) throws Exception {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
