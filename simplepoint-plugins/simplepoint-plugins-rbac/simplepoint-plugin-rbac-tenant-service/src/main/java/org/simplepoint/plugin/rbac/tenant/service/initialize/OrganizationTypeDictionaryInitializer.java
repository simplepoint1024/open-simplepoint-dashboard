package org.simplepoint.plugin.rbac.tenant.service.initialize;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Initializes the organization type dictionary and its default items on first startup.
 */
@Component
public class OrganizationTypeDictionaryInitializer {

  private static final String INIT_MODULE = "platform-organization-type-dictionary";

  /**
   * Registers the organization type dictionary bootstrap contribution.
   *
   * @param dictionaryService     the dictionary service
   * @param dictionaryItemService the dictionary item service
   * @return the platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution organizationTypeDictionaryBootstrapContribution(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    return () -> BootstrapContribution.versioned(
        "rbac-tenant",
        "dictionary",
        INIT_MODULE,
        "1",
        300,
        () -> initializeOrganizationTypeDictionary(
            dictionaryService,
            dictionaryItemService
        )
    );
  }

  private void initializeOrganizationTypeDictionary(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    Dictionary dictionary = ensureDictionary(dictionaryService);
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "group",
        item -> {
          item.setName("集团");
          item.setI18nKey("organizations.type.group");
          item.setDescription("用于表示集团级组织");
          item.setSort(10);
        }
    );
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "unit",
        item -> {
          item.setName("单位");
          item.setI18nKey("organizations.type.unit");
          item.setDescription("用于表示单位级组织");
          item.setSort(20);
        }
    );
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "department",
        item -> {
          item.setName("部门");
          item.setI18nKey("organizations.type.department");
          item.setDescription("用于表示部门级组织");
          item.setSort(30);
        }
    );
    ensureDictionaryItem(
        dictionaryItemService,
        dictionary.getCode(),
        "team",
        item -> {
          item.setName("小组");
          item.setI18nKey("organizations.type.team");
          item.setDescription("用于表示小组级组织");
          item.setSort(40);
        }
    );
  }

  private Dictionary ensureDictionary(DictionaryService dictionaryService) {
    return dictionaryService.findAll(Map.of("code", TenantDictionaryCodes.ORGANIZATION_TYPE))
        .stream()
        .findFirst()
        .map(existing -> {
          existing.setName("组织类型");
          existing.setDescription("用于定义组织机构的层级类型");
          existing.setSort(30);
          existing.setEnabled(Boolean.TRUE);
          return (Dictionary) dictionaryService.modifyById(existing);
        })
        .orElseGet(() -> {
          Dictionary dictionary = new Dictionary();
          dictionary.setName("组织类型");
          dictionary.setCode(TenantDictionaryCodes.ORGANIZATION_TYPE);
          dictionary.setDescription("用于定义组织机构的层级类型");
          dictionary.setSort(30);
          dictionary.setEnabled(Boolean.TRUE);
          return dictionaryService.create(dictionary);
        });
  }

  private void ensureDictionaryItem(
      DictionaryItemService dictionaryItemService,
      String dictionaryCode,
      String value,
      Consumer<DictionaryItem> customizer
  ) {
    DictionaryItem current = dictionaryItemService.findAll(Map.of("dictionaryCode", dictionaryCode))
        .stream()
        .filter(item -> Objects.equals(item.getValue(), value))
        .findFirst()
        .orElse(null);
    if (current == null) {
      DictionaryItem item = new DictionaryItem();
      item.setDictionaryCode(dictionaryCode);
      item.setValue(value);
      customizer.accept(item);
      item.setEnabled(Boolean.TRUE);
      dictionaryItemService.create(item);
      return;
    }
    customizer.accept(current);
    current.setDictionaryCode(dictionaryCode);
    current.setValue(value);
    current.setEnabled(Boolean.TRUE);
    dictionaryItemService.modifyById(current);
  }
}
