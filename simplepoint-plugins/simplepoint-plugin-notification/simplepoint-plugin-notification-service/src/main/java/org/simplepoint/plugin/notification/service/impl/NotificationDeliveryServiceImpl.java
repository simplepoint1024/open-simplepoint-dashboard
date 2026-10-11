package org.simplepoint.plugin.notification.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationCategory;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;
import org.simplepoint.plugin.notification.api.repository.SystemNotificationRepository;
import org.simplepoint.plugin.notification.api.service.NotificationDeliveryService;
import org.simplepoint.plugin.notification.api.service.NotificationManagementService;
import org.simplepoint.plugin.notification.api.vo.NotificationDeliveryCommand;
import org.simplepoint.plugin.personal.api.PersonalInboxGateway;
import org.simplepoint.plugin.personal.api.PersonalModels.Delivery;
import org.simplepoint.plugin.personal.api.PersonalModels.DeliveryTarget;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trusted service-router adapter. Tenant-scoped deliveries use My Messages; unscoped deliveries
 * retain the system-notification channel. The in-process personal gateway is never exposed as RPC.
 */
@Service
@RemoteProvider
public class NotificationDeliveryServiceImpl implements NotificationDeliveryService {

  private final SystemNotificationRepository repository;
  private final NotificationManagementService managementService;
  private final ObjectProvider<PersonalInboxGateway> personalInbox;

  /** Creates the idempotent notification delivery provider. */
  public NotificationDeliveryServiceImpl(
      final SystemNotificationRepository repository,
      final NotificationManagementService managementService,
      final ObjectProvider<PersonalInboxGateway> personalInbox) {
    this.repository = repository;
    this.managementService = managementService;
    this.personalInbox = personalInbox;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public String deliver(final NotificationDeliveryCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("通知投递内容不能为空");
    }
    String source = required(command.source(), "通知来源不能为空", 64);
    String externalKey = required(command.externalKey(), "通知幂等键不能为空", 200);
    if (command.tenantId() != null) {
      return deliverPersonal(command, source, externalKey);
    }
    return repository
        .findBySourceAndExternalKey(source, externalKey)
        .map(SystemNotification::getId)
        .orElseGet(() -> publish(command, source, externalKey));
  }

  private String deliverPersonal(
      NotificationDeliveryCommand command, String source, String externalKey) {
    String tenant = required(command.tenantId(), "通知组织不能为空", 64);
    if (tenant.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("通知组织不能包含空白字符");
    }
    if (command.expireAt() != null) {
      throw new IllegalArgumentException("个人流程消息不支持过期时间");
    }
    PersonalInboxGateway gateway = personalInbox.getIfAvailable();
    if (gateway == null) {
      // Never silently downgrade a tenant-isolated message into a global notification.
      throw new IllegalStateException("个人消息投递服务尚未就绪");
    }
    int priority =
        switch (command.priority()) {
          case LOW -> 0;
          case null -> 1;
          case NORMAL -> 1;
          case HIGH -> 2;
          case URGENT -> 3;
        };
    return gateway
        .deliverMessage(
            new DeliveryTarget(
                required(command.targetUserId(), "通知目标用户不能为空", 64), "TENANT:" + tenant),
            new Delivery(
                source,
                personalKey(externalKey),
                required(command.title(), "通知标题不能为空", 200),
                required(command.content(), "通知内容不能为空", 8000),
                priority,
                null,
                command.linkUrl()))
        .id();
  }

  private static String personalKey(String externalKey) {
    // The personal inbox key is limited to 160 characters, while the existing delivery contract
    // permits 200. Hash every key (not only long ones) to avoid prefix/short-key collisions.
    try {
      return "notification:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(externalKey.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String publish(
      final NotificationDeliveryCommand command, final String source, final String externalKey) {
    SystemNotification notification = new SystemNotification();
    notification.setSource(source);
    notification.setExternalKey(externalKey);
    notification.setTitle(required(command.title(), "通知标题不能为空", 200));
    notification.setContent(required(command.content(), "通知内容不能为空", 8000));
    notification.setAudienceType(NotificationAudienceType.USER);
    notification.setAudienceId(required(command.targetUserId(), "通知目标用户不能为空", 64));
    notification.setCategory(NotificationCategory.SYSTEM);
    notification.setPriority(
        command.priority() == null ? NotificationPriority.NORMAL : command.priority());
    notification.setLinkUrl(command.linkUrl());
    notification.setExpireAt(command.expireAt());
    SystemNotification created = managementService.create(notification);
    return managementService.publish(created.getId()).getId();
  }

  private static String required(final String value, final String message, final int maxLength) {
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
