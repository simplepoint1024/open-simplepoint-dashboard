package org.simplepoint.plugin.rbac.tenant.service.initialize;

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

class TenantTypeDictionaryInitializerTest {

  @Test
  void contribution_hasStableMetadata() {
    TenantTypeDictionaryInitializer initializer = new TenantTypeDictionaryInitializer();
    BootstrapContribution contribution = initializer.tenantTypeDictionaryBootstrapContribution(
        mock(DictionaryService.class),
        mock(DictionaryItemService.class)
    ).contribution();

    assertThat(contribution.moduleCode()).isEqualTo("rbac-tenant");
    assertThat(contribution.contributionType()).isEqualTo("dictionary");
    assertThat(contribution.contributionKey()).isEqualTo("platform-tenant-type-dictionary");
    assertThat(contribution.version()).isEqualTo("1");
    assertThat(contribution.order()).isEqualTo(305);
  }

  @Test
  @SuppressWarnings("unchecked")
  void bootstrapContribution_createsDictionaryAndAllTenantTypes() throws Exception {
    DictionaryService dictionaryService = mock(DictionaryService.class);
    final DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    Dictionary createdDictionary = new Dictionary();
    createdDictionary.setCode("tenant.type");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class))).thenReturn(createdDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TenantTypeDictionaryInitializer initializer = new TenantTypeDictionaryInitializer();
    initializer.tenantTypeDictionaryBootstrapContribution(dictionaryService, dictionaryItemService)
        .contribution()
        .action()
        .run();

    verify(dictionaryService).create(any(Dictionary.class));
    ArgumentCaptor<DictionaryItem> itemCaptor = ArgumentCaptor.forClass(DictionaryItem.class);
    verify(dictionaryItemService, times(2)).create(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .extracting(DictionaryItem::getValue, DictionaryItem::getI18nKey, DictionaryItem::getSort)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                "ORGANIZATION", "tenants.type.ORGANIZATION", 10
            ),
            org.assertj.core.groups.Tuple.tuple(
                "PERSONAL", "tenants.type.PERSONAL", 20
            )
        );
  }
}
