package org.simplepoint.plugin.notification.api.service;

import org.simplepoint.plugin.notification.api.vo.NotificationModels.AudienceContext;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.InboxItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Authenticated user's durable notification inbox. */
public interface NotificationInboxService {

  /** Resolves the current user and workspace for inbox and stream operations. */
  AudienceContext currentAudience();

  /** Pages effective notifications visible in the active workspace. */
  Page<InboxItem> inbox(Pageable pageable);

  /** Counts effective unread notifications. */
  long unreadCount();

  /** Marks one visible notification as read idempotently. */
  void markRead(String notificationId);

  /** Marks all currently visible notifications as read idempotently. */
  void markAllRead();
}
