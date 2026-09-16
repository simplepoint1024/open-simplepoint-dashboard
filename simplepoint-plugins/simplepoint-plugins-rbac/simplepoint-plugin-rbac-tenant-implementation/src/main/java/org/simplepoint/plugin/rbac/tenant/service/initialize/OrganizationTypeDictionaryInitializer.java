package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
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
    var dictionary = BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        TenantDictionaryCodes.ORGANIZATION_TYPE,
        "组织类型",
        "用于定义组织机构的层级类型",
        30
    );
    BuiltInDictionaryRegistrar.ensureItem(
        dictionaryItemService,
        dictionary.getCode(),
        "group",
        "集团",
        "organizations.type.group",
        "用于表示集团级组织",
        10
    );
    BuiltInDictionaryRegistrar.ensureItem(
        dictionaryItemService,
        dictionary.getCode(),
        "unit",
        "单位",
        "organizations.type.unit",
        "用于表示单位级组织",
        20
    );
    BuiltInDictionaryRegistrar.ensureItem(
        dictionaryItemService,
        dictionary.getCode(),
        "department",
        "部门",
        "organizations.type.department",
        "用于表示部门级组织",
        30
    );
    BuiltInDictionaryRegistrar.ensureItem(
        dictionaryItemService,
        dictionary.getCode(),
        "team",
        "小组",
        "organizations.type.team",
        "用于表示小组级组织",
        40
    );
  }
}
