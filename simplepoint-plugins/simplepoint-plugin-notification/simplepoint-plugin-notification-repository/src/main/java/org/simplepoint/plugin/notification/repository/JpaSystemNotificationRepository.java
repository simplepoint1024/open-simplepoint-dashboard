package org.simplepoint.plugin.notification.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.repository.SystemNotificationRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA persistence for managed system notifications. */
@Repository
public interface JpaSystemNotificationRepository
    extends BaseRepository<SystemNotification, String>, SystemNotificationRepository {

  @Override
  @Query("""
      select n from SystemNotification n
      where n.id = :id and n.deletedAt is null
      """)
  Optional<SystemNotification> findActiveById(@Param("id") String id);
}
