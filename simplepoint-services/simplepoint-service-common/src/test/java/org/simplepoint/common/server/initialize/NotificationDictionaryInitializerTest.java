package org.simplepoint.common.server.initialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;

class NotificationDictionaryInitializerTest {

  @Test
  void contributionHasStableMetadata() {
    NotificationDictionaryInitializer initializer = new NotificationDictionaryInitializer();

    BootstrapContribution contribution = initializer.notificationDictionaryBootstrapContribution(
        mock(DictionaryService.class),
        mock(DictionaryItemService.class)
    ).contribution();

    assertThat(contribution.moduleCode()).isEqualTo("notification");
    assertThat(contribution.contributionType()).isEqualTo("dictionary");
    assertThat(contribution.contributionKey()).isEqualTo("system-notification-dictionaries");
    assertThat(contribution.version()).isEqualTo("1");
    assertThat(contribution.order()).isEqualTo(310);
  }

  @Test
  @SuppressWarnings("unchecked")
  void contributionCreatesAllNotificationDictionariesAndItems() throws Exception {
    DictionaryService dictionaryService = mock(DictionaryService.class);
    DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    NotificationDictionaryInitializer initializer = new NotificationDictionaryInitializer();
    initializer.notificationDictionaryBootstrapContribution(
        dictionaryService,
        dictionaryItemService
    ).contribution().action().run();

    ArgumentCaptor<Dictionary> dictionaryCaptor = ArgumentCaptor.forClass(Dictionary.class);
    verify(dictionaryService, times(4)).create(dictionaryCaptor.capture());
    assertThat(dictionaryCaptor.getAllValues())
        .extracting(Dictionary::getCode)
        .containsExactly(
            "notification.category",
            "notification.priority",
            "notification.audience-type",
            "notification.status"
        );

    ArgumentCaptor<DictionaryItem> itemCaptor = ArgumentCaptor.forClass(DictionaryItem.class);
    verify(dictionaryItemService, times(15)).create(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .extracting(DictionaryItem::getI18nKey)
        .contains(
            "notifications.category.ANNOUNCEMENT",
            "notifications.priority.URGENT",
            "notifications.audience.TENANT",
            "notifications.status.REVOKED"
        );
  }
}
