package org.simplepoint.plugin.rbac.menu.rest.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.security.entity.Button;
import org.simplepoint.security.service.ButtonService;
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
 * REST controller for managing button entities.
 *
 * <p>This controller provides CRUD operations for {@link Button} objects,
 * allowing creation, modification, deletion, and retrieval of button records.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@RestController
@RequestMapping("/buttons")
@Tag(name = "按钮管理", description = "用于管理系统中的按钮")
public class ButtonController extends BaseController<ButtonService, Button, String> {
  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public ButtonController(final ButtonService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of buttons based on the specified filter attributes.
   *
   * @param attributes a map of filtering attributes
   * @param pageable   pagination information
   * @return a paginated list of button records wrapped in {@link Response}
   * @throws Exception if an error occurs during retrieval
   */
  @GetMapping
  @Operation(summary = "分页查询按钮", description = "根据提供的属性和分页参数，检索按钮的分页列表")
  public Response<Page<Button>> limit(@RequestParam Map<String, String> attributes,
                                      Pageable pageable)
      throws Exception {
    return limit(service.limit(attributes, pageable), Button.class);
  }

  /**
   * Adds a new button record.
   *
   * @param data the {@link Button} instance to be added
   * @return the newly created button record wrapped in {@link Response}
   * @throws Exception if an error occurs during addition
   */
  @PostMapping
  @Operation(summary = "添加新按钮", description = "将新的按钮添加到系统中")
  public Response<Button> add(@RequestBody Button data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Modifies an existing button record by its ID.
   *
   * @param data the {@link Button} instance containing updated information
   * @return the modified button record wrapped in {@link Response}
   * @throws Exception if an error occurs during modification
   */
  @PutMapping
  @Operation(summary = "更新按钮信息", description = "更新系统中现有按钮的信息")
  public Response<Button> modify(@RequestBody Button data) throws Exception {
    return ok(service.modifyById(data));
  }

  /**
   * Removes button records by their IDs.
   *
   * @param ids a comma-separated string of button IDs to be removed
   * @return a response indicating the operation was successful
   * @throws Exception if an error occurs during removal
   */
  @DeleteMapping
  @Operation(summary = "删除按钮", description = "根据提供的按钮ID删除一个或多个按钮")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) throws Exception {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
