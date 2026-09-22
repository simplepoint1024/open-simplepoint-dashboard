package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Registers the tenant membership classification shared by organization and member screens. */
@Component
public class TenantMemberUserTypeDictionaryInitializer {
  @Bean
  public PlatformBootstrapContribution tenantMemberUserTypeDictionaryBootstrapContribution(
      DictionaryService dictionaries, DictionaryItemService items) {
    return () -> BootstrapContribution.versioned("rbac-tenant", "dictionary",
        "tenant-member-user-type", "1", 310, () -> {
          var dictionary = BuiltInDictionaryRegistrar.ensureDictionary(dictionaries,
              TenantDictionaryCodes.TENANT_MEMBER_USER_TYPE, "租户成员用户类型",
              "区分租户中的内部、外部和引用用户", 40);
          BuiltInDictionaryRegistrar.ensureItem(items, dictionary.getCode(), "INTERNAL", "内部",
              "tenant-members.userType.INTERNAL", "当前组织内部用户", 10);
          BuiltInDictionaryRegistrar.ensureItem(items, dictionary.getCode(), "EXTERNAL", "外部",
              "tenant-members.userType.EXTERNAL", "外部协作用户", 20);
          BuiltInDictionaryRegistrar.ensureItem(items, dictionary.getCode(), "REFERENCE", "引用",
              "tenant-members.userType.REFERENCE", "引用的租户用户", 30);
        });
  }
}
