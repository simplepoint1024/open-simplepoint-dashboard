package org.simplepoint.plugin.notification.api.vo;

import java.time.Instant;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationCategory;
import org.simplepoint.plugin.notification.api.model.NotificationChangeType;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;

/** User inbox and proactive-delivery contracts. */
public final class NotificationModels {

  private NotificationModels() {
  }

  /** Immutable identity and active workspace used for audience matching. */
  public record AudienceContext(
      String userId,
      AuthorizationScopeType scopeType,
      String tenantId
  ) {
  }

  /** Notification projection returned to an authenticated user. */
  public record InboxItem(
      String id,
      String title,
      String content,
      NotificationCategory category,
      NotificationPriority priority,
      String linkUrl,
      Instant publishAt,
      Instant expireAt,
      boolean read,
      Instant readAt
  ) {
  }

  /** Internal application event consumed by proactive delivery adapters. */
  public record NotificationChangedEvent(
      NotificationChangeType type,
      String notificationId,
      NotificationAudienceType audienceType,
      String audienceId,
      Instant occurredAt
  ) {
  }

  /** Minimal SSE event; clients always reconcile from the durable inbox. */
  public record PushEvent(
      NotificationChangeType type,
      String notificationId,
      Instant occurredAt
  ) {
  }
}
