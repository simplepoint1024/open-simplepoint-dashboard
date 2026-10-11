package org.simplepoint.plugin.notification.service.impl;

import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationCategory;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;
import org.simplepoint.plugin.notification.api.repository.SystemNotificationRepository;
import org.simplepoint.plugin.notification.api.service.NotificationDeliveryService;
import org.simplepoint.plugin.notification.api.service.NotificationManagementService;
import org.simplepoint.plugin.notification.api.vo.NotificationDeliveryCommand;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes service-originated messages through the existing notification inbox. */
@Service
@RemoteProvider
public class NotificationDeliveryServiceImpl implements NotificationDeliveryService {

  private final SystemNotificationRepository repository;
  private final NotificationManagementService managementService;

  /** Creates the idempotent notification delivery provider. */
  public NotificationDeliveryServiceImpl(
      final SystemNotificationRepository repository,
      final NotificationManagementService managementService
  ) {
    this.repository = repository;
    this.managementService = managementService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public String deliver(final NotificationDeliveryCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("通知投递内容不能为空");
    }
    String source = required(command.source(), "通知来源不能为空", 64);
    String externalKey = required(command.externalKey(), "通知幂等键不能为空", 200);
    return repository.findBySourceAndExternalKey(source, externalKey)
        .map(SystemNotification::getId)
        .orElseGet(() -> publish(command, source, externalKey));
  }

  private String publish(
      final NotificationDeliveryCommand command,
      final String source,
      final String externalKey
  ) {
    SystemNotification notification = new SystemNotification();
    notification.setSource(source);
    notification.setExternalKey(externalKey);
    notification.setTitle(required(command.title(), "通知标题不能为空", 200));
    notification.setContent(required(command.content(), "通知内容不能为空", 8000));
    notification.setAudienceType(NotificationAudienceType.USER);
    notification.setAudienceId(required(command.targetUserId(), "通知目标用户不能为空", 64));
    notification.setCategory(NotificationCategory.SYSTEM);
    notification.setPriority(command.priority() == null
        ? NotificationPriority.NORMAL : command.priority());
    notification.setLinkUrl(command.linkUrl());
    notification.setExpireAt(command.expireAt());
    SystemNotification created = managementService.create(notification);
    return managementService.publish(created.getId()).getId();
  }

  private static String required(
      final String value,
      final String message,
      final int maxLength
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(message + "，且不能超过 " + maxLength + " 个字符");
    }
    return normalized;
  }
}
