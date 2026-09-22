package org.simplepoint.plugin.personal.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.personal.api.PersonalModels.ActionInput;
import org.simplepoint.plugin.personal.api.PersonalModels.EventView;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemInput;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemView;
import org.simplepoint.plugin.personal.api.PersonalModels.MessageView;
import org.simplepoint.plugin.personal.api.PersonalModels.Overview;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoInput;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoView;
import org.simplepoint.plugin.personal.api.PersonalService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Personal APIs are authenticated; ownership comes exclusively from authorization context. */
@RestController
@RequestMapping("/personal")
@PreAuthorize("isAuthenticated()")
@Tag(name = "我的工作", description = "当前用户当前工作空间的事项、待办和业务消息")
public class PersonalController {
  private static final ObjectMapper FILTER_MAPPER =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final PersonalService service;

  /** Delegates business validation and ownership to the personal service. */
  public PersonalController(PersonalService service) {
    this.service = service;
  }

  private static Map<String, String> parseFilters(String filters) {
    if (filters == null || filters.isBlank()) {
      return Map.of();
    }
    if (filters.length() > 8192) {
      throw new IllegalArgumentException("表头筛选条件过长");
    }
    try {
      var node = FILTER_MAPPER.readTree(filters);
      if (!node.isObject() || node.size() > 16) {
        throw new IllegalArgumentException("无效的表头筛选条件");
      }
      Map<String, String> result = new LinkedHashMap<>();
      node.properties()
          .forEach(
              entry -> {
                if (!entry.getValue().isTextual()) {
                  throw new IllegalArgumentException("表头筛选值必须为字符串");
                }
                result.put(entry.getKey(), entry.getValue().textValue());
              });
      return result;
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("无效的表头筛选条件");
    }
  }

  /** Handles the authenticated read endpoint /overview. */
  @GetMapping("/overview")
  @Operation(summary = "首页个人工作摘要")
  public Response<Overview> overview() {
    return Response.okay(service.overview());
  }

  /** Handles the authenticated read endpoint /items. */
  @GetMapping("/items")
  @Operation(summary = "分页查询我的事项")
  public Response<Page<ItemView>> items(
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "priority", required = false) Integer priority,
      @RequestParam(name = "view", defaultValue = "ACTIVE") String view,
      @RequestParam(name = "filters", required = false) String filters,
      Pageable page) {
    return Response.okay(
        service.items(keyword, status, category, priority, view, page, parseFilters(filters)));
  }

  /** Handles the authenticated read endpoint /items/{id}. */
  @GetMapping("/items/{id}")
  public Response<ItemView> item(@PathVariable("id") String id) {
    return Response.okay(service.item(id));
  }

  /** Handles the authenticated write endpoint /items. */
  @PostMapping("/items")
  @Operation(summary = "新建个人事项")
  public Response<ItemView> createItem(@RequestBody ItemInput input) {
    return Response.okay(service.createItem(input));
  }

  /** Handles the authenticated write endpoint /items/{id}. */
  @PutMapping("/items/{id}")
  @Operation(summary = "编辑个人事项（版本检查）")
  public Response<ItemView> updateItem(
      @PathVariable("id") String id, @RequestBody ItemInput input) {
    return Response.okay(service.updateItem(id, input));
  }

  /** Handles the authenticated write endpoint /items/{id}/actions. */
  @PostMapping("/items/{id}/actions")
  @Operation(summary = "事项状态流转、归档、回收站和备注")
  public Response<ItemView> itemAction(
      @PathVariable("id") String id, @RequestBody ActionInput input) {
    return Response.okay(service.itemAction(id, input));
  }

  /** Handles the authenticated read endpoint /items/{id}/history. */
  @GetMapping("/items/{id}/history")
  public Response<Page<EventView>> history(@PathVariable("id") String id, Pageable page) {
    return Response.okay(service.history(id, page));
  }

  /** Handles the authenticated read endpoint /todos. */
  @GetMapping("/todos")
  public Response<Page<TodoView>> todos(
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "filters", required = false) String filters,
      Pageable page) {
    return Response.okay(service.todos(keyword, status, page, parseFilters(filters)));
  }

  /** Handles the authenticated read endpoint /todos/{id}. */
  @GetMapping("/todos/{id}")
  public Response<TodoView> todo(@PathVariable("id") String id) {
    return Response.okay(service.todo(id));
  }

  /** Handles the authenticated write endpoint /todos. */
  @PostMapping("/todos")
  public Response<TodoView> createTodo(@RequestBody TodoInput input) {
    return Response.okay(service.createTodo(input));
  }

  /** Handles the authenticated write endpoint /todos/{id}/actions. */
  @PostMapping("/todos/{id}/actions")
  public Response<TodoView> todoAction(
      @PathVariable("id") String id, @RequestBody ActionInput input) {
    return Response.okay(service.todoAction(id, input));
  }

  /** Handles the authenticated read endpoint /messages. */
  @GetMapping("/messages")
  public Response<Page<MessageView>> messages(
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly,
      @RequestParam(name = "archived", defaultValue = "false") boolean archived,
      @RequestParam(name = "filters", required = false) String filters,
      Pageable page) {
    return Response.okay(
        service.messages(keyword, unreadOnly, archived, page, parseFilters(filters)));
  }

  /** Handles the authenticated read endpoint /messages/{id}. */
  @GetMapping("/messages/{id}")
  public Response<MessageView> message(@PathVariable("id") String id) {
    return Response.okay(service.message(id));
  }

  /** Handles the authenticated write endpoint /messages/{id}/actions. */
  @PostMapping("/messages/{id}/actions")
  public Response<MessageView> messageAction(
      @PathVariable("id") String id, @RequestBody ActionInput input) {
    return Response.okay(service.messageAction(id, input));
  }

  /** Handles the authenticated write endpoint /messages/read-all. */
  @PutMapping("/messages/read-all")
  public Response<Map<String, Boolean>> readAll() {
    service.readAllMessages();
    return Response.okay(Map.of("success", true));
  }
}
