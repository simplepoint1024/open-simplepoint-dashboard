package org.simplepoint.plugin.notification.api.repository;

import java.time.Instant;
import java.util.List;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.AudienceContext;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.InboxItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Optimized audience-aware inbox queries. */
public interface NotificationInboxRepository {

  /** Pages currently effective notifications visible to an audience. */
  Page<InboxItem> findVisible(
      AudienceContext audience,
      Instant now,
      Pageable pageable
  );

  /** Counts currently effective unread notifications. */
  long countUnread(AudienceContext audience, Instant now);

  /** Checks visibility before a user-state mutation. */
  boolean isVisible(String notificationId, AudienceContext audience, Instant now);

  /** Lists all currently effective notification identifiers for mark-all-read. */
  List<String> findVisibleIds(AudienceContext audience, Instant now);
}
