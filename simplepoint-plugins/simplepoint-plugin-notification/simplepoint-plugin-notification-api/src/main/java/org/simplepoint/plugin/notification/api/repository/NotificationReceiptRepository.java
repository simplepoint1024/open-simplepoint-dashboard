package org.simplepoint.plugin.notification.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.notification.api.entity.NotificationReceipt;

/** Persistence contract for sparse per-user notification receipts. */
public interface NotificationReceiptRepository
    extends BaseRepository<NotificationReceipt, String> {

  /** Finds one user's receipt for a notification. */
  Optional<NotificationReceipt> findByNotificationIdAndUserId(
      String notificationId,
      String userId
  );

  /** Finds the user's receipts for the requested notifications. */
  List<NotificationReceipt> findAllByUserIdAndNotificationIdIn(
      String userId,
      List<String> notificationIds
  );
}
