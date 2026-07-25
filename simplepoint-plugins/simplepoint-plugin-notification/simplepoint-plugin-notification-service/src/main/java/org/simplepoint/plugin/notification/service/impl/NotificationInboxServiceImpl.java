package org.simplepoint.plugin.notification.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.notification.api.entity.NotificationReceipt;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationChangeType;
import org.simplepoint.plugin.notification.api.repository.NotificationInboxRepository;
import org.simplepoint.plugin.notification.api.repository.NotificationReceiptRepository;
import org.simplepoint.plugin.notification.api.service.NotificationInboxService;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.AudienceContext;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.InboxItem;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.NotificationChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default authenticated user notification inbox service. */
@Service
public class NotificationInboxServiceImpl implements NotificationInboxService {

  private static final int MAX_PAGE_SIZE = 100;

  private static final int RECEIPT_BATCH_SIZE = 500;

  private final NotificationInboxRepository inboxRepository;

  private final NotificationReceiptRepository receiptRepository;

  private final ApplicationEventPublisher eventPublisher;

  /**
   * Creates the inbox service.
   *
   * @param inboxRepository   optimized inbox queries
   * @param receiptRepository sparse read-state repository
   * @param eventPublisher    application event publisher
   */
  public NotificationInboxServiceImpl(
      final NotificationInboxRepository inboxRepository,
      final NotificationReceiptRepository receiptRepository,
      final ApplicationEventPublisher eventPublisher
  ) {
    this.inboxRepository = inboxRepository;
    this.receiptRepository = receiptRepository;
    this.eventPublisher = eventPublisher;
  }

  /** {@inheritDoc} */
  @Override
  public AudienceContext currentAudience() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    if (context == null) {
      throw new IllegalStateException("缺少授权上下文");
    }
    String userId = requireValue(context.getUserId(), "当前用户未登录");
    AuthorizationScopeType scopeType = context.getScopeType() == null
        ? AuthorizationScopeType.PERSONAL : context.getScopeType();
    String tenantId = trimToNull(context.getAttribute("X-Tenant-Id"));
    if (scopeType == AuthorizationScopeType.TENANT && tenantId == null) {
      throw new IllegalStateException("租户工作区缺少租户上下文");
    }
    return new AudienceContext(userId, scopeType, tenantId);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public Page<InboxItem> inbox(final Pageable pageable) {
    return inboxRepository.findVisible(
        currentAudience(),
        Instant.now(),
        bounded(pageable)
    );
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public long unreadCount() {
    return inboxRepository.countUnread(currentAudience(), Instant.now());
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void markRead(final String notificationId) {
    String id = requireValue(notificationId, "通知 ID 不能为空");
    AudienceContext audience = currentAudience();
    Instant now = Instant.now();
    if (!inboxRepository.isVisible(id, audience, now)) {
      throw new IllegalArgumentException("通知不存在或当前用户不可见");
    }
    if (receiptRepository.findByNotificationIdAndUserId(id, audience.userId()).isPresent()) {
      return;
    }
    NotificationReceipt receipt = new NotificationReceipt();
    receipt.setNotificationId(id);
    receipt.setUserId(audience.userId());
    receipt.setReadAt(now);
    receiptRepository.save(receipt);
    publishUserState(audience.userId(), id, now);
  }

  /** {@inheritDoc} */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void markAllRead() {
    AudienceContext audience = currentAudience();
    Instant now = Instant.now();
    List<String> ids = inboxRepository.findVisibleIds(audience, now);
    if (ids.isEmpty()) {
      return;
    }
    boolean changed = false;
    for (int start = 0; start < ids.size(); start += RECEIPT_BATCH_SIZE) {
      List<String> batch = ids.subList(
          start,
          Math.min(ids.size(), start + RECEIPT_BATCH_SIZE)
      );
      Set<String> existing = new HashSet<>();
      receiptRepository.findAllByUserIdAndNotificationIdIn(audience.userId(), batch)
          .forEach(receipt -> existing.add(receipt.getNotificationId()));
      List<NotificationReceipt> receipts = new ArrayList<>();
      for (String id : batch) {
        if (!existing.contains(id)) {
          NotificationReceipt receipt = new NotificationReceipt();
          receipt.setNotificationId(id);
          receipt.setUserId(audience.userId());
          receipt.setReadAt(now);
          receipts.add(receipt);
        }
      }
      if (!receipts.isEmpty()) {
        receiptRepository.saveAll(receipts);
        changed = true;
      }
    }
    if (changed) {
      publishUserState(audience.userId(), null, now);
    }
  }

  private void publishUserState(
      final String userId,
      final String notificationId,
      final Instant occurredAt
  ) {
    eventPublisher.publishEvent(new NotificationChangedEvent(
        NotificationChangeType.USER_STATE_CHANGED,
        notificationId,
        NotificationAudienceType.USER,
        userId,
        occurredAt
    ));
  }

  private static Pageable bounded(final Pageable pageable) {
    if (pageable == null || pageable.isUnpaged()) {
      return PageRequest.of(0, 20);
    }
    return PageRequest.of(
        Math.max(0, pageable.getPageNumber()),
        Math.min(MAX_PAGE_SIZE, Math.max(1, pageable.getPageSize()))
    );
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
}
