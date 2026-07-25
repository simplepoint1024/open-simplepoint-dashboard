package org.simplepoint.plugin.notification.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.notification.api.constants.NotificationPaths;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.service.NotificationManagementService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform administrator endpoints for notification lifecycle management. */
@RestController
@RequestMapping(NotificationPaths.MANAGEMENT)
@Tag(name = "系统通知管理", description = "管理系统通知草稿、发布和撤回")
public class NotificationManagementController
    extends BaseController<NotificationManagementService, SystemNotification, String> {

  /**
   * Creates the notification management controller.
   *
   * @param service notification management service
   */
  public NotificationManagementController(final NotificationManagementService service) {
    super(service);
  }

  /** Pages notification definitions and schema-driven actions. */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('notifications.view')")
  @Operation(summary = "分页查询系统通知")
  public Response<Page<SystemNotification>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), SystemNotification.class);
  }

  /** Creates a draft notification. */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('notifications.create')")
  @Operation(summary = "创建系统通知草稿")
  public Response<?> add(@RequestBody final SystemNotification data) {
    return invoke(() -> service.create(data));
  }

  /** Updates a draft notification. */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('notifications.edit')")
  @Operation(summary = "修改系统通知草稿")
  public Response<?> modify(@RequestBody final SystemNotification data) {
    return invoke(() -> service.modifyById(data));
  }

  /** Deletes one or more draft notifications. */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('notifications.delete')")
  @Operation(summary = "删除系统通知草稿")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    return invoke(() -> {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return idSet;
    });
  }

  /** Publishes one draft and proactively pushes an inbox reconciliation event. */
  @PostMapping("/{id}/publish")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('notifications.publish')")
  @Operation(summary = "发布系统通知")
  public Response<?> publish(@PathVariable("id") final String id) {
    return invoke(() -> service.publish(id));
  }

  /** Revokes one published notification and removes it from active inboxes. */
  @PostMapping("/{id}/revoke")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('notifications.revoke')")
  @Operation(summary = "撤回系统通知")
  public Response<?> revoke(@PathVariable("id") final String id) {
    return invoke(() -> service.revoke(id));
  }

  /** Copies one revoked notification into a new editable draft. */
  @PostMapping("/{id}/duplicate")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('notifications.duplicate')")
  @Operation(summary = "复制已撤回通知为新草稿")
  public Response<?> duplicate(@PathVariable("id") final String id) {
    return invoke(() -> service.duplicateAsDraft(id));
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return ok(operation.get());
    } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException ex) {
      return Response.of(
          ResponseEntity.badRequest()
              .contentType(MediaType.TEXT_PLAIN)
              .body(ex.getMessage())
      );
    }
  }
}
