package org.simplepoint.plugin.notification.api.service;

import java.util.Map;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Administrator workflow for drafting and publishing system notifications. */
public interface NotificationManagementService
    extends BaseService<SystemNotification, String> {

  /** Pages non-deleted notifications for platform management. */
  <S extends SystemNotification> Page<S> limit(
      Map<String, String> attributes,
      Pageable pageable
  );

  /** Publishes a validated draft immediately. */
  SystemNotification publish(String id);

  /** Revokes a published notification and removes it from active inboxes. */
  SystemNotification revoke(String id);

  /**
   * Copies a revoked notification into a new draft with a new identity.
   *
   * <p>The original notification remains immutable for audit purposes, while the new identity
   * prevents historical read receipts from affecting a future publication.</p>
   */
  SystemNotification duplicateAsDraft(String id);
}
