package org.simplepoint.plugin.notification.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/** Sparse per-user delivery state. A row is created only after user interaction. */
@Data
@Entity
@Table(name = "simpoint_notification_receipts",
    uniqueConstraints = @UniqueConstraint(name = "uk_simpoint_notification_receipt",
        columnNames = {"notification_id", "user_id"}),
    indexes = @Index(name = "idx_simpoint_notification_receipt_user",
        columnList = "user_id, read_at"))
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class NotificationReceipt extends BaseEntityImpl<String> {

  @Column(name = "notification_id", length = 64, nullable = false)
  private String notificationId;

  @Column(name = "user_id", length = 64, nullable = false)
  private String userId;

  @Column(name = "read_at", nullable = false)
  private Instant readAt;
}
