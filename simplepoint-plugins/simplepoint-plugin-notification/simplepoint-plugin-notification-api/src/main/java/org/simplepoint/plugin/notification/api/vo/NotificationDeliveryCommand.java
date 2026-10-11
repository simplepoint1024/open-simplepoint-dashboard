package org.simplepoint.plugin.notification.api.vo;

import java.time.Instant;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;

/** Idempotent user-targeted notification requested by another platform service. */
public record NotificationDeliveryCommand(
    String source,
    String externalKey,
    String targetUserId,
    String title,
    String content,
    String linkUrl,
    NotificationPriority priority,
    Instant expireAt
) {
}
