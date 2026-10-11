package org.simplepoint.plugin.notification.rest.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;
import org.simplepoint.plugin.notification.api.service.NotificationInboxService;
import org.simplepoint.plugin.notification.api.service.NotificationManagementService;
import org.simplepoint.plugin.notification.rest.push.NotificationSseBroker;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerPathVariableTest {

  private NotificationManagementService managementService;

  private NotificationInboxService inboxService;

  private MockMvc managementMvc;

  private MockMvc inboxMvc;

  @BeforeEach
  void setUp() {
    managementService = mock(NotificationManagementService.class);
    inboxService = mock(NotificationInboxService.class);
    managementMvc = MockMvcBuilders
        .standaloneSetup(new NotificationManagementController(managementService))
        .build();
    inboxMvc = MockMvcBuilders
        .standaloneSetup(new NotificationInboxController(
            inboxService,
            mock(NotificationSseBroker.class)
        ))
        .build();
  }

  @Test
  void publishBindsNotificationId() throws Exception {
    SystemNotification notification = notification("notification-1");
    when(managementService.publish(notification.getId())).thenReturn(notification);

    managementMvc.perform(post("/system/notifications/{id}/publish", notification.getId()))
        .andExpect(status().isOk());

    verify(managementService).publish(notification.getId());
  }

  @Test
  void revokeBindsNotificationId() throws Exception {
    SystemNotification notification = notification("notification-2");
    when(managementService.revoke(notification.getId())).thenReturn(notification);

    managementMvc.perform(post("/system/notifications/{id}/revoke", notification.getId()))
        .andExpect(status().isOk());

    verify(managementService).revoke(notification.getId());
  }

  @Test
  void duplicateBindsNotificationId() throws Exception {
    SystemNotification notification = notification("notification-3");
    when(managementService.duplicateAsDraft(notification.getId())).thenReturn(notification);

    managementMvc.perform(post("/system/notifications/{id}/duplicate", notification.getId()))
        .andExpect(status().isOk());

    verify(managementService).duplicateAsDraft(notification.getId());
  }

  @Test
  void changedLifecycleReturnsClientSafeBadRequestWithoutPretendingItSucceeded() throws Exception {
    when(managementService.publish("notification-changed")).thenThrow(new IllegalStateException("仅草稿通知可以发布"));
    managementMvc.perform(post("/system/notifications/{id}/publish", "notification-changed"))
        .andExpect(status().isBadRequest()).andExpect(content().string("仅草稿通知可以发布"));
    verify(managementService).publish("notification-changed");
  }

  @Test
  void missingDuplicateSourceReturnsBadRequestAndKeepsItsCapturedPath() throws Exception {
    when(managementService.duplicateAsDraft("notification-missing")).thenThrow(new java.util.NoSuchElementException("通知不存在"));
    managementMvc.perform(post("/system/notifications/{id}/duplicate", "notification-missing"))
        .andExpect(status().isBadRequest()).andExpect(content().string("通知不存在"));
    verify(managementService).duplicateAsDraft("notification-missing");
  }

  @Test
  void markReadBindsNotificationId() throws Exception {
    inboxMvc.perform(put("/notifications/{id}/read", "notification-4"))
        .andExpect(status().isOk());

    verify(inboxService).markRead("notification-4");
  }

  private static SystemNotification notification(final String id) {
    SystemNotification notification = new SystemNotification();
    notification.setId(id);
    return notification;
  }
}
