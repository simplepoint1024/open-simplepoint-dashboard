package org.simplepoint.plugin.i18n.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.entity.Message;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.core.utils.StringUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * I18nMessagesController is a REST controller that handles HTTP requests related to internationalization
 * messages in the SimplePoint application. This controller provides endpoints for retrieving and managing
 * message data, facilitating operations such as fetching message lists and details.
 */
@RestController
@RequestMapping("/i18n/messages")
@Tag(name = "i18n消息管理", description = "用于管理系统中的国际化消息配置")
public class I18nMessagesController extends BaseController<I18nMessageService, Message, String> {
  /**
   * Constructor initializing the controller with a service instance. 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public I18nMessagesController(I18nMessageService service) {
    super(service);
  }

  /**
   * Retrieves a paginated list of messages based on the provided attributes and pageable parameters.
   *
   * @param attributes a map of attributes to filter the messages
   *                   用于过滤消息的属性映射
   * @param pageable   the pagination and sorting information
   *                   分页和排序信息
   * @return a paginated response containing messages that match the given attributes 包含符合给定属性的消息的分页响应
   */
  @GetMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('messages.view')")
  @Operation(summary = "分页查询消息", description = "根据提供的属性和分页参数，检索消息的分页列表")
  public Response<Page<Message>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    return limit(service.limit(attributes, pageable), Message.class);
  }

  /**
   * Retrieves global messages based on the provided locale and namespace.
   *
   * @param locale the locale for which messages are requested
   *               请求消息的语言环境
   * @param ns     the namespace for which messages are requested
   *               请求消息的命名空间
   * @return a response containing a map of global messages 包含全局消息映射的响应
   */
  @GetMapping("/mapping")
  @Operation(summary = "获取全局消息", description = "根据提供的语言环境和命名空间，获取全局消息映射")
  public Response<Map<String, String>> mapping(
      @RequestParam(name = "locale") String locale,
      @RequestParam(name = "ns", required = false) String ns
  ) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return ok(service.mapping(locale, ns));
  }

  /**
   * Adds a new message to the system.
   *
   * @param data the message data to be added
   *             要添加的消息数据
   * @return a response containing the added message 包含已添加消息的响应
   * @throws Exception if an error occurs during addition
   *                   如果添加过程中发生错误
   */
  @PostMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('messages.create')")
  @Operation(summary = "添加消息", description = "添加一个新的消息到系统中")
  public Response<Message> add(@RequestBody Message data) throws Exception {
    return ok(service.add(data));
  }

  /**
   * Modifies an existing message in the system.
   *
   * @param data the message data to be modified
   *             要修改的消息数据
   * @return a response containing the modified message 包含已修改消息的响应
   */
  @PutMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('messages.edit')")
  @Operation(summary = "修改消息", description = "修改一个已存在的消息信息")
  public Response<Message> modify(@RequestBody Message data) {
    return ok(service.modifyById(data));
  }

  /**
   * Deletes one or more messages based on the provided message ID collection.
   *
   * @param ids a comma-separated string of message IDs to be deleted
   *            要删除的消息ID的逗号分隔字符串
   * @return a response containing the set of deleted message IDs 包含已删除消息ID集合的响应
   */
  @DeleteMapping
  @PreAuthorize("hasRole('SYSTEM') or hasAuthority('messages.delete')")
  @Operation(summary = "删除消息", description = "根据提供的消息ID集合，删除一个或多个消息")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
