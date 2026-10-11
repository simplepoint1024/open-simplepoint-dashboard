package org.simplepoint.plugin.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.model.NotificationPriority;
import org.simplepoint.plugin.notification.api.repository.SystemNotificationRepository;
import org.simplepoint.plugin.notification.api.service.NotificationManagementService;
import org.simplepoint.plugin.notification.api.vo.NotificationDeliveryCommand;
import org.simplepoint.plugin.personal.api.PersonalInboxGateway;
import org.simplepoint.plugin.personal.api.PersonalModels.Delivery;
import org.simplepoint.plugin.personal.api.PersonalModels.DeliveryTarget;
import org.simplepoint.plugin.personal.api.PersonalModels.MessageView;
import org.springframework.beans.factory.ObjectProvider;

class NotificationDeliveryServiceImplTest {
  private final SystemNotificationRepository repository = mock(SystemNotificationRepository.class);
  private final NotificationManagementService management =
      mock(NotificationManagementService.class);
  private final PersonalInboxGateway inbox = mock(PersonalInboxGateway.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<PersonalInboxGateway> provider = mock(ObjectProvider.class);

  private final NotificationDeliveryServiceImpl service =
      new NotificationDeliveryServiceImpl(repository, management, provider);

  @Test
  void tenantDeliveryUsesOnlyPersonalMessagesAndStableBoundedKeys() {
    when(provider.getIfAvailable()).thenReturn(inbox);
    when(inbox.deliverMessage(any(), any())).thenReturn(message());
    var command = command("x".repeat(200), "tenant-one", null);
    assertThat(service.deliver(command)).isEqualTo("message");
    assertThat(service.deliver(command)).isEqualTo("message");
    var targets = ArgumentCaptor.forClass(DeliveryTarget.class);
    var deliveries = ArgumentCaptor.forClass(Delivery.class);
    verify(inbox, org.mockito.Mockito.times(2))
        .deliverMessage(targets.capture(), deliveries.capture());
    assertThat(targets.getAllValues())
        .containsOnly(new DeliveryTarget("user", "TENANT:tenant-one"));
    assertThat(deliveries.getAllValues()).containsOnly(deliveries.getValue());
    assertThat(deliveries.getValue().sourceKey()).hasSize(77).startsWith("notification:");
    assertThat(deliveries.getValue().priority()).isEqualTo(2);
    assertThat(deliveries.getValue().linkPath()).isEqualTo("/my/todos?task=task");
    verifyNoInteractions(repository, management);
  }

  @Test
  void missingPersonalGatewayFailsWithoutLeakingIntoGlobalInbox() {
    assertThatThrownBy(() -> service.deliver(command("key", "tenant", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("尚未就绪");
    verifyNoInteractions(repository, management, inbox);
  }

  @Test
  void blankInvalidTenantAndUnsupportedExpiryAreNotSilentlyDowngraded() {
    for (String tenant : new String[] {"", " ", "tenant one"}) {
      assertThatThrownBy(() -> service.deliver(command("key", tenant, null)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> service.deliver(command("key", "tenant", Instant.now())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("过期");
    verifyNoInteractions(repository, management, inbox);
  }

  @Test
  void unscopedRetriesKeepExistingSystemNotificationAndDoNotCreatePersonalCopies() {
    var existing = new SystemNotification();
    existing.setId("existing");
    when(repository.findBySourceAndExternalKey("workflow", "key"))
        .thenReturn(Optional.of(existing));
    var command =
        new NotificationDeliveryCommand(
            "workflow",
            "key",
            "user",
            "title",
            "content",
            "/my/items",
            NotificationPriority.NORMAL,
            null);
    assertThat(service.deliver(command)).isEqualTo("existing");
    verifyNoInteractions(inbox, management);
  }

  @Test
  void gatewayValidationAndRetryableFailurePropagateWithoutSystemFallback() {
    when(provider.getIfAvailable()).thenReturn(inbox);
    when(inbox.deliverMessage(any(), any()))
        .thenThrow(new IllegalArgumentException("unsafe internal link"));
    assertThatThrownBy(() -> service.deliver(command("key", "tenant", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe internal link");
    verifyNoInteractions(repository, management);
  }

  private NotificationDeliveryCommand command(String key, String tenant, Instant expires) {
    return new NotificationDeliveryCommand(
        "workflow",
        key,
        "user",
        "title",
        "content",
        "/my/todos?task=task",
        NotificationPriority.HIGH,
        expires,
        tenant);
  }

  private MessageView message() {
    return new MessageView(
        "message",
        0L,
        "title",
        "content",
        2,
        "workflow",
        "/my/todos?task=task",
        null,
        false,
        Instant.now());
  }
}
