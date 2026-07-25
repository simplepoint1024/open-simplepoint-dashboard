package org.simplepoint.plugin.notification.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.notification.api.entity.NotificationReceipt;
import org.simplepoint.plugin.notification.api.repository.NotificationReceiptRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA persistence for sparse notification receipts. */
@Repository
public interface JpaNotificationReceiptRepository
    extends BaseRepository<NotificationReceipt, String>, NotificationReceiptRepository {

  @Override
  @Query("""
      select r from NotificationReceipt r
      where r.notificationId = :notificationId
        and r.userId = :userId
        and r.deletedAt is null
      """)
  Optional<NotificationReceipt> findByNotificationIdAndUserId(
      @Param("notificationId") String notificationId,
      @Param("userId") String userId
  );

  @Override
  @Query("""
      select r from NotificationReceipt r
      where r.userId = :userId
        and r.notificationId in :notificationIds
        and r.deletedAt is null
      """)
  List<NotificationReceipt> findAllByUserIdAndNotificationIdIn(
      @Param("userId") String userId,
      @Param("notificationIds") List<String> notificationIds
  );
}
