package org.simplepoint.plugin.notification.service.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationCategory;
import org.simplepoint.plugin.notification.api.model.NotificationChangeType;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;
import org.simplepoint.plugin.notification.api.model.NotificationStatus;
import org.simplepoint.plugin.notification.api.repository.SystemNotificationRepository;
import org.simplepoint.plugin.notification.api.service.NotificationManagementService;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.NotificationChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default administrator workflow for durable system notifications. */
@Service
public class NotificationManagementServiceImpl
    extends BaseServiceImpl<SystemNotificationRepository, SystemNotification, String>
    implements NotificationManagementService {

  private final SystemNotificationRepository repository;

  private final ApplicationEventPublisher eventPublisher;

  /**
   * Creates the notification management service.
   *
   * @param repository             notification repository
   * @param detailsProviderService schema and audit dialect provider
   * @param eventPublisher         application event publisher
   */
  public NotificationManagementServiceImpl(
      final SystemNotificationRepository repository,
      final DetailsProviderService detailsProviderService,
      final ApplicationEventPublisher eventPublisher
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.eventPublisher = eventPublisher;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends SystemNotification> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "title");
    return super.limit(normalized, pageable);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends SystemNotification> S create(final S entity) {
    normalizeAndValidate(entity);
    entity.setStatus(NotificationStatus.DRAFT);
    entity.setPublishAt(null);
    entity.setPublishedAt(null);
    return super.create(entity);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends SystemNotification> SystemNotification modifyById(final S entity) {
    SystemNotification current = active(requireEntityId(entity));
    requireDraft(current, "仅草稿通知可以修改");
    normalizeAndValidate(entity);
    entity.setStatus(current.getStatus());
    entity.setPublishAt(current.getPublishAt());
    entity.setPublishedAt(current.getPublishedAt());
    return super.modifyById(entity);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    ids.forEach(id -> requireDeletable(active(id)));
    super.removeByIds(ids);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public SystemNotification publish(final String id) {
    SystemNotification notification = active(requireValue(id, "通知 ID 不能为空"));
    requireDraft(notification, "仅草稿通知可以发布");
    normalizeAndValidate(notification);
    Instant now = Instant.now();
    notification.setStatus(NotificationStatus.PUBLISHED);
    notification.setPublishAt(now);
    notification.setPublishedAt(now);
    SystemNotification saved = repository.save(notification);
    eventPublisher.publishEvent(changed(NotificationChangeType.PUBLISHED, saved, now));
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public SystemNotification revoke(final String id) {
    SystemNotification notification = active(requireValue(id, "通知 ID 不能为空"));
    if (notification.getStatus() != NotificationStatus.PUBLISHED) {
      throw new IllegalStateException("仅已发布通知可以撤回");
    }
    Instant now = Instant.now();
    notification.setStatus(NotificationStatus.REVOKED);
    SystemNotification saved = repository.save(notification);
    eventPublisher.publishEvent(changed(NotificationChangeType.REVOKED, saved, now));
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public SystemNotification duplicateAsDraft(final String id) {
    SystemNotification source = active(requireValue(id, "通知 ID 不能为空"));
    if (source.getStatus() != NotificationStatus.REVOKED) {
      throw new IllegalStateException("仅已撤回通知可以复制为新草稿");
    }

    SystemNotification draft = new SystemNotification();
    draft.setTitle(source.getTitle());
    draft.setContent(source.getContent());
    draft.setCategory(source.getCategory());
    draft.setPriority(source.getPriority());
    draft.setAudienceType(source.getAudienceType());
    draft.setAudienceId(source.getAudienceId());
    draft.setLinkUrl(source.getLinkUrl());
    if (source.getExpireAt() != null && source.getExpireAt().isAfter(Instant.now())) {
      draft.setExpireAt(source.getExpireAt());
    }
    return create(draft);
  }

  private SystemNotification active(final String id) {
    return repository.findActiveById(id)
        .orElseThrow(() -> new NoSuchElementException("通知不存在: " + id));
  }

  private static void requireDraft(
      final SystemNotification notification,
      final String message
  ) {
    if (notification.getStatus() != NotificationStatus.DRAFT) {
      throw new IllegalStateException(message);
    }
  }

  private static void requireDeletable(final SystemNotification notification) {
    if (notification.getStatus() != NotificationStatus.DRAFT
        && notification.getStatus() != NotificationStatus.REVOKED) {
      throw new IllegalStateException("仅草稿或已撤回通知可以删除");
    }
  }

  private static NotificationChangedEvent changed(
      final NotificationChangeType type,
      final SystemNotification notification,
      final Instant occurredAt
  ) {
    return new NotificationChangedEvent(
        type,
        notification.getId(),
        notification.getAudienceType(),
        notification.getAudienceId(),
        occurredAt
    );
  }

  private static void normalizeAndValidate(final SystemNotification notification) {
    if (notification == null) {
      throw new IllegalArgumentException("通知不能为空");
    }
    notification.setTitle(requireValue(notification.getTitle(), "通知标题不能为空"));
    notification.setContent(requireValue(notification.getContent(), "通知内容不能为空"));
    if (notification.getTitle().length() > 200) {
      throw new IllegalArgumentException("通知标题不能超过 200 个字符");
    }
    if (notification.getContent().length() > 8000) {
      throw new IllegalArgumentException("通知内容不能超过 8000 个字符");
    }
    if (notification.getCategory() == null) {
      notification.setCategory(NotificationCategory.SYSTEM);
    }
    if (notification.getPriority() == null) {
      notification.setPriority(NotificationPriority.NORMAL);
    }
    if (notification.getAudienceType() == null) {
      notification.setAudienceType(NotificationAudienceType.ALL);
    }
    if (notification.getAudienceType() == NotificationAudienceType.TENANT
        || notification.getAudienceType() == NotificationAudienceType.USER) {
      notification.setAudienceId(requireValue(
          notification.getAudienceId(),
          "租户或用户定向通知必须填写目标 ID"
      ));
    } else {
      notification.setAudienceId(null);
    }
    if (notification.getExpireAt() != null
        && !notification.getExpireAt().isAfter(Instant.now())) {
      throw new IllegalArgumentException("过期时间必须晚于当前时间");
    }
    notification.setLinkUrl(validateLink(notification.getLinkUrl()));
  }

  private static String validateLink(final String value) {
    String link = trimToNull(value);
    if (link == null) {
      return null;
    }
    if (link.length() > 1024) {
      throw new IllegalArgumentException("跳转链接不能超过 1024 个字符");
    }
    if (link.startsWith("/") && !link.startsWith("//")) {
      return link;
    }
    try {
      URI uri = new URI(link);
      if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
        return uri.toASCIIString();
      }
    } catch (URISyntaxException ignored) {
      // Converted to a client-safe validation error below.
    }
    throw new IllegalArgumentException("跳转链接仅支持站内绝对路径或 HTTPS 地址");
  }

  private static String requireValue(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static void normalizeLikeQuery(
      final Map<String, String> attributes,
      final String field
  ) {
    String value = attributes.get(field);
    if (value != null && !value.isBlank() && !value.startsWith("like:")) {
      attributes.put(field, "like:" + value.trim());
    }
  }

  private static String requireEntityId(final SystemNotification entity) {
    if (entity == null) {
      throw new IllegalArgumentException("通知不能为空");
    }
    return requireValue(entity.getId(), "通知 ID 不能为空");
  }
}
