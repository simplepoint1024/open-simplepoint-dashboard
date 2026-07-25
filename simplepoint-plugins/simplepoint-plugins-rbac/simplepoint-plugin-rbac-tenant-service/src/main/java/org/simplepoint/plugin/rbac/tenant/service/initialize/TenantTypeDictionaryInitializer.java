package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantType;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Registers tenant workspace types as a reusable, internationalized dictionary.
 */
@Component
public class TenantTypeDictionaryInitializer {

  private static final String INIT_MODULE = "platform-tenant-type-dictionary";

  /**
   * Registers the tenant type dictionary bootstrap contribution.
   *
   * @param dictionaryService     the dictionary service
   * @param dictionaryItemService the dictionary item service
   * @return the platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution tenantTypeDictionaryBootstrapContribution(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    return () -> BootstrapContribution.versioned(
        "rbac-tenant",
        "dictionary",
        INIT_MODULE,
        "1",
        305,
        () -> initializeTenantTypeDictionary(dictionaryService, dictionaryItemService)
    );
  }

  private void initializeTenantTypeDictionary(
      DictionaryService dictionaryService,
      DictionaryItemService dictionaryItemService
  ) {
    var dictionary = BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        TenantDictionaryCodes.TENANT_TYPE,
        "租户类型",
        "用于定义租户工作空间的使用形态",
        35
    );
    for (TenantType tenantType : TenantType.values()) {
      String value = tenantType.name();
      BuiltInDictionaryRegistrar.ensureItem(
          dictionaryItemService,
          dictionary.getCode(),
          value,
          value,
          "tenants.type." + value,
          "租户工作空间类型 " + value,
          tenantType == TenantType.ORGANIZATION ? 10 : 20
      );
    }
  }
}
