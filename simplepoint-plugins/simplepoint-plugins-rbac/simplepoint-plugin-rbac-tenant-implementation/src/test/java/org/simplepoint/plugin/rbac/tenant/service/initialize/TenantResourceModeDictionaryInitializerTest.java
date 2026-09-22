package org.simplepoint.plugin.rbac.tenant.service.initialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;

class TenantResourceModeDictionaryInitializerTest {
  @Test
  @SuppressWarnings("unchecked")
  void registersBothInternationalizedModes() throws Exception {
    DictionaryService dictionaries = mock(DictionaryService.class);
    DictionaryItemService items = mock(DictionaryItemService.class);
    Dictionary dictionary = new Dictionary();
    dictionary.setCode("tenant.resource-mode");
    when(dictionaries.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaries.create(any(Dictionary.class))).thenReturn(dictionary);
    when(items.findAll(any(Map.class))).thenReturn(List.of());
    when(items.create(any(DictionaryItem.class))).thenAnswer(call -> call.getArgument(0));

    var contribution = new TenantResourceModeDictionaryInitializer()
        .tenantResourceModeDictionaryBootstrapContribution(dictionaries, items).contribution();
    assertThat(contribution.contributionKey()).isEqualTo("platform-tenant-resource-mode-dictionary");
    contribution.action().run();

    ArgumentCaptor<DictionaryItem> captured = ArgumentCaptor.forClass(DictionaryItem.class);
    verify(items, times(2)).create(captured.capture());
    assertThat(captured.getAllValues()).extracting(DictionaryItem::getValue, DictionaryItem::getI18nKey)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("PLATFORM", "tenants.resourceMode.PLATFORM"),
            org.assertj.core.groups.Tuple.tuple("CUSTOM", "tenants.resourceMode.CUSTOM"));
  }
}
