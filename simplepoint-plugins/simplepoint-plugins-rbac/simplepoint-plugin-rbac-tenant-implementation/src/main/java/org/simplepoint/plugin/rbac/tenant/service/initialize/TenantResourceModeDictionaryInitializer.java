package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Registers the organization workspace resource loading modes. */
@Component
public class TenantResourceModeDictionaryInitializer {
  @Bean
  public PlatformBootstrapContribution tenantResourceModeDictionaryBootstrapContribution(
      DictionaryService dictionaries, DictionaryItemService items) {
    return () -> BootstrapContribution.versioned("rbac-tenant", "dictionary",
        "platform-tenant-resource-mode-dictionary", "1", 306, () -> {
          var dictionary = BuiltInDictionaryRegistrar.ensureDictionary(dictionaries,
              TenantDictionaryCodes.TENANT_RESOURCE_MODE, "资源加载类型",
              "组织租户的资源目录加载方式", 36);
          for (TenantResourceMode mode : TenantResourceMode.values()) {
            BuiltInDictionaryRegistrar.ensureItem(items, dictionary.getCode(), mode.name(),
                mode.name(), "tenants.resourceMode." + mode.name(),
                "租户资源加载类型 " + mode.name(),
                mode == TenantResourceMode.PLATFORM ? 10 : 20);
          }
        });
  }
}
