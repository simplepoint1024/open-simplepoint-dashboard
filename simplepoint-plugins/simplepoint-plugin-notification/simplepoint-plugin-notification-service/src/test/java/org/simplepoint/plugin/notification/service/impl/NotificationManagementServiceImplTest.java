package org.simplepoint.plugin.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.model.NotificationAudienceType;
import org.simplepoint.plugin.notification.api.model.NotificationChangeType;
import org.simplepoint.plugin.notification.api.model.NotificationStatus;
import org.simplepoint.plugin.notification.api.repository.SystemNotificationRepository;
import org.simplepoint.plugin.notification.api.vo.NotificationModels.NotificationChangedEvent;
import org.springframework.context.ApplicationEventPublisher;

class NotificationManagementServiceImplTest {

  private final SystemNotificationRepository repository =
      mock(SystemNotificationRepository.class);

  private final DetailsProviderService detailsProviderService =
      mock(DetailsProviderService.class);

  private final ApplicationEventPublisher eventPublisher =
      mock(ApplicationEventPublisher.class);

  private NotificationManagementServiceImpl service;

  @BeforeEach
  void setUp() {
    when(detailsProviderService.getDialects(ModifyDataAuditingService.class))
        .thenReturn(List.of());
    when(repository.save(any(SystemNotification.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    service = new NotificationManagementServiceImpl(
        repository,
        detailsProviderService,
        eventPublisher
    );
  }

  @Test
  void createForcesDraftAndNormalizesDefaults() {
    SystemNotification notification = draft();
    notification.setCategory(null);
    notification.setPriority(null);
    notification.setStatus(NotificationStatus.PUBLISHED);

    SystemNotification saved = service.create(notification);

    assertThat(saved.getStatus()).isEqualTo(NotificationStatus.DRAFT);
    assertThat(saved.getCategory().name()).isEqualTo("SYSTEM");
    assertThat(saved.getPriority().name()).isEqualTo("NORMAL");
    assertThat(saved.getAudienceId()).isNull();
    assertThat(saved.getPublishAt()).isNull();
  }

  @Test
  void createRejectsUnsafeLink() {
    SystemNotification notification = draft();
    notification.setLinkUrl("javascript:alert(1)");

    assertThatThrownBy(() -> service.create(notification))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS");
    verify(repository, never()).save(any());
  }

  @Test
  void publishLocksLifecycleAndEmitsAudienceEvent() {
    SystemNotification notification = draft();
    notification.setId("notification-1");
    notification.setAudienceType(NotificationAudienceType.TENANT);
    notification.setAudienceId("tenant-1");
    when(repository.findActiveById("notification-1"))
        .thenReturn(Optional.of(notification));

    SystemNotification saved = service.publish("notification-1");

    assertThat(saved.getStatus()).isEqualTo(NotificationStatus.PUBLISHED);
    assertThat(saved.getPublishAt()).isNotNull();
    assertThat(saved.getPublishedAt()).isEqualTo(saved.getPublishAt());
    ArgumentCaptor<NotificationChangedEvent> event =
        ArgumentCaptor.forClass(NotificationChangedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().type()).isEqualTo(NotificationChangeType.PUBLISHED);
    assertThat(event.getValue().audienceId()).isEqualTo("tenant-1");
  }

  @Test
  void publishedNotificationCannotBeModified() {
    SystemNotification notification = draft();
    notification.setId("notification-1");
    notification.setStatus(NotificationStatus.PUBLISHED);
    when(repository.findActiveById("notification-1"))
        .thenReturn(Optional.of(notification));

    assertThatThrownBy(() -> service.modifyById(notification))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("草稿");
  }

  @Test
  void revokedNotificationIsDuplicatedAsIndependentDraft() {
    SystemNotification revoked = draft();
    revoked.setId("notification-1");
    revoked.setStatus(NotificationStatus.REVOKED);
    revoked.setPublishAt(Instant.parse("2026-01-01T00:00:00Z"));
    revoked.setPublishedAt(revoked.getPublishAt());
    revoked.setExpireAt(Instant.parse("2026-01-02T00:00:00Z"));
    when(repository.findActiveById("notification-1")).thenReturn(Optional.of(revoked));

    SystemNotification duplicate = service.duplicateAsDraft("notification-1");

    assertThat(duplicate).isNotSameAs(revoked);
    assertThat(duplicate.getId()).isNull();
    assertThat(duplicate.getStatus()).isEqualTo(NotificationStatus.DRAFT);
    assertThat(duplicate.getTitle()).isEqualTo("System maintenance");
    assertThat(duplicate.getContent()).isEqualTo("Maintenance begins at 02:00.");
    assertThat(duplicate.getPublishAt()).isNull();
    assertThat(duplicate.getPublishedAt()).isNull();
    assertThat(duplicate.getExpireAt()).isNull();
  }

  @Test
  void nonRevokedNotificationCannotBeDuplicatedAsDraft() {
    SystemNotification published = draft();
    published.setId("notification-1");
    published.setStatus(NotificationStatus.PUBLISHED);
    when(repository.findActiveById("notification-1")).thenReturn(Optional.of(published));

    assertThatThrownBy(() -> service.duplicateAsDraft("notification-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已撤回");
  }

  @Test
  void revokedNotificationCanBeDeleted() {
    SystemNotification revoked = draft();
    revoked.setId("notification-1");
    revoked.setStatus(NotificationStatus.REVOKED);
    when(repository.findActiveById("notification-1")).thenReturn(Optional.of(revoked));
    when(repository.findAllByIds(List.of("notification-1"))).thenReturn(List.of(revoked));

    service.removeByIds(List.of("notification-1"));

    verify(repository).deleteByIds(List.of("notification-1"));
  }

  @Test
  void publishedNotificationCannotBeDeleted() {
    SystemNotification published = draft();
    published.setId("notification-1");
    published.setStatus(NotificationStatus.PUBLISHED);
    when(repository.findActiveById("notification-1")).thenReturn(Optional.of(published));

    assertThatThrownBy(() -> service.removeByIds(List.of("notification-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("草稿或已撤回");
    verify(repository, never()).deleteByIds(any());
  }

  private static SystemNotification draft() {
    SystemNotification notification = new SystemNotification();
    notification.setTitle("  System maintenance  ");
    notification.setContent("  Maintenance begins at 02:00.  ");
    notification.setAudienceType(NotificationAudienceType.ALL);
    notification.setStatus(NotificationStatus.DRAFT);
    return notification;
  }
}
