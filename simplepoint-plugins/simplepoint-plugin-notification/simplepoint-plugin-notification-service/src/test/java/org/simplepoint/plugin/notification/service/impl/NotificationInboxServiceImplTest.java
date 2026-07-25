package org.simplepoint.plugin.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.notification.api.entity.NotificationReceipt;
import org.simplepoint.plugin.notification.api.model.NotificationChangeType;
import org.simplepoint.plugin.notification.api.repository.NotificationInboxRepository;
import org.simplepoint.plugin.notification.api.repository.NotificationReceiptRepository;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.NotificationChangedEvent;
import org.springframework.context.ApplicationEventPublisher;

class NotificationInboxServiceImplTest {

  private final NotificationInboxRepository inboxRepository =
      mock(NotificationInboxRepository.class);

  private final NotificationReceiptRepository receiptRepository =
      mock(NotificationReceiptRepository.class);

  private final ApplicationEventPublisher eventPublisher =
      mock(ApplicationEventPublisher.class);

  private MockedStatic<AuthorizationContextHolder> contextHolder;

  private NotificationInboxServiceImpl service;

  @BeforeEach
  void setUp() {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-1");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-1"));
    contextHolder = mockStatic(AuthorizationContextHolder.class);
    contextHolder.when(AuthorizationContextHolder::getContext).thenReturn(context);
    service = new NotificationInboxServiceImpl(
        inboxRepository,
        receiptRepository,
        eventPublisher
    );
  }

  @AfterEach
  void tearDown() {
    contextHolder.close();
  }

  @Test
  void currentAudienceCapturesImmutableTenantScope() {
    var audience = service.currentAudience();

    assertThat(audience.userId()).isEqualTo("user-1");
    assertThat(audience.scopeType()).isEqualTo(AuthorizationScopeType.TENANT);
    assertThat(audience.tenantId()).isEqualTo("tenant-1");
  }

  @Test
  void markReadCreatesSparseReceiptAndUserEvent() {
    when(inboxRepository.isVisible(any(), any(), any())).thenReturn(true);
    when(receiptRepository.findByNotificationIdAndUserId(
        "notification-1",
        "user-1"
    )).thenReturn(Optional.empty());

    service.markRead("notification-1");

    ArgumentCaptor<NotificationReceipt> receipt =
        ArgumentCaptor.forClass(NotificationReceipt.class);
    verify(receiptRepository).save(receipt.capture());
    assertThat(receipt.getValue().getNotificationId()).isEqualTo("notification-1");
    assertThat(receipt.getValue().getUserId()).isEqualTo("user-1");
    assertThat(receipt.getValue().getReadAt()).isNotNull();
    ArgumentCaptor<NotificationChangedEvent> event =
        ArgumentCaptor.forClass(NotificationChangedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().type())
        .isEqualTo(NotificationChangeType.USER_STATE_CHANGED);
    assertThat(event.getValue().audienceId()).isEqualTo("user-1");
  }

  @Test
  void markReadIsIdempotent() {
    when(inboxRepository.isVisible(any(), any(), any())).thenReturn(true);
    when(receiptRepository.findByNotificationIdAndUserId(
        "notification-1",
        "user-1"
    )).thenReturn(Optional.of(new NotificationReceipt()));

    service.markRead("notification-1");

    verify(receiptRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void markReadRejectsInvisibleNotification() {
    when(inboxRepository.isVisible(any(), any(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.markRead("notification-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不可见");
    verify(receiptRepository, never()).save(any());
  }
}
