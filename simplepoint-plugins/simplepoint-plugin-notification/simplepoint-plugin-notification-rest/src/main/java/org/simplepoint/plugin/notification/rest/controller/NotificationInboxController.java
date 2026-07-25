package org.simplepoint.plugin.notification.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.notification.api.constants.NotificationPaths;
import org.simplepoint.plugin.notification.api.service.NotificationInboxService;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.InboxItem;
import org.simplepoint.plugin.notification.rest.push.NotificationSseBroker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authenticated notification inbox and proactive delivery endpoints. */
@RestController
@RequestMapping(NotificationPaths.INBOX)
@Tag(name = "通知中心", description = "当前用户通知收件箱、已读状态和主动推送")
public class NotificationInboxController {

  private final NotificationInboxService service;

  private final NotificationSseBroker sseBroker;

  /**
   * Creates the inbox controller.
   *
   * @param service   notification inbox service
   * @param sseBroker proactive SSE broker
   */
  public NotificationInboxController(
      final NotificationInboxService service,
      final NotificationSseBroker sseBroker
  ) {
    this.service = service;
    this.sseBroker = sseBroker;
  }

  /** Pages the current user's visible notifications. */
  @GetMapping("/inbox")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "分页查询当前用户通知")
  public Response<Page<InboxItem>> inbox(final Pageable pageable) {
    return Response.okay(service.inbox(pageable));
  }

  /** Returns the current user's effective unread count. */
  @GetMapping("/unread-count")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "查询未读通知数量")
  public Response<Map<String, Long>> unreadCount() {
    return Response.okay(Map.of("count", service.unreadCount()));
  }

  /** Marks one visible notification as read idempotently. */
  @PutMapping("/{id}/read")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "标记通知已读")
  public Response<Map<String, Boolean>> markRead(@PathVariable("id") final String id) {
    service.markRead(id);
    return Response.okay(Map.of("success", true));
  }

  /** Marks all currently visible notifications as read idempotently. */
  @PutMapping("/read-all")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "全部标记已读")
  public Response<Map<String, Boolean>> markAllRead() {
    service.markAllRead();
    return Response.okay(Map.of("success", true));
  }

  /** Opens an audience-aware server-sent event stream. */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "订阅通知主动推送")
  public SseEmitter stream(final HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    response.setHeader("X-Accel-Buffering", "no");
    return sseBroker.connect(service.currentAudience());
  }
}
