package org.simplepoint.plugin.rbac.tenant.service.initialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;

class OrganizationTypeDictionaryInitializerTest {

  @Test
  void contribution_hasStableMetadata() {
    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    BootstrapContribution contribution = initializer.organizationTypeDictionaryBootstrapContribution(
        mock(DictionaryService.class),
        mock(DictionaryItemService.class)
    ).contribution();

    assertThat(contribution.moduleCode()).isEqualTo("rbac-tenant");
    assertThat(contribution.contributionType()).isEqualTo("dictionary");
    assertThat(contribution.contributionKey()).isEqualTo("platform-organization-type-dictionary");
    assertThat(contribution.version()).isEqualTo("1");
    assertThat(contribution.order()).isEqualTo(300);
  }

  @Test
  @SuppressWarnings("unchecked")
  void bootstrapContribution_noExistingData_createsDictionaryAndItems() throws Exception {
    final DictionaryService dictionaryService = mock(DictionaryService.class);
    final DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    Dictionary createdDictionary = new Dictionary();
    createdDictionary.setCode("organization.type");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class))).thenReturn(createdDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    initializer.organizationTypeDictionaryBootstrapContribution(dictionaryService, dictionaryItemService)
        .contribution()
        .action()
        .run();

    verify(dictionaryService).create(any(Dictionary.class));
    verify(dictionaryItemService, times(4)).create(any(DictionaryItem.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void bootstrapContribution_existingDictionary_modifiesInsteadOfCreate() throws Exception {
    final DictionaryService dictionaryService = mock(DictionaryService.class);
    final DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    Dictionary existingDictionary = new Dictionary();
    existingDictionary.setCode("organization.type");
    existingDictionary.setName("Old Name");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of(existingDictionary));
    when(dictionaryService.modifyById(any(Dictionary.class))).thenReturn(existingDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryItemService.create(any(DictionaryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    initializer.organizationTypeDictionaryBootstrapContribution(dictionaryService, dictionaryItemService)
        .contribution()
        .action()
        .run();

    verify(dictionaryService).modifyById(existingDictionary);
  }

  @Test
  @SuppressWarnings("unchecked")
  void bootstrapContribution_existingDictionaryItem_modifiesInsteadOfCreate() throws Exception {
    final DictionaryService dictionaryService = mock(DictionaryService.class);
    final DictionaryItemService dictionaryItemService = mock(DictionaryItemService.class);
    Dictionary createdDictionary = new Dictionary();
    createdDictionary.setCode("organization.type");
    DictionaryItem existingGroup = new DictionaryItem();
    existingGroup.setValue("group");

    when(dictionaryService.findAll(any(Map.class))).thenReturn(List.of());
    when(dictionaryService.create(any(Dictionary.class))).thenReturn(createdDictionary);
    when(dictionaryItemService.findAll(any(Map.class))).thenReturn(List.of(existingGroup));
    when(dictionaryItemService.modifyById(any(DictionaryItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    OrganizationTypeDictionaryInitializer initializer = new OrganizationTypeDictionaryInitializer();
    initializer.organizationTypeDictionaryBootstrapContribution(dictionaryService, dictionaryItemService)
        .contribution()
        .action()
        .run();

    verify(dictionaryItemService, atLeastOnce()).modifyById(any(DictionaryItem.class));
  }
}
