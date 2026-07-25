package org.simplepoint.plugin.notification.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;

/** Persistence contract for managed system notifications. */
public interface SystemNotificationRepository
    extends BaseRepository<SystemNotification, String> {

  /** Finds a non-deleted notification by identifier. */
  Optional<SystemNotification> findActiveById(String id);
}
