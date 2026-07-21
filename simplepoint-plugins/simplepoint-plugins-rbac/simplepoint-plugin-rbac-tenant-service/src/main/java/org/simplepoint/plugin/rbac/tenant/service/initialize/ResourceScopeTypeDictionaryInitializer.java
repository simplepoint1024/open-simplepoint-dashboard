package org.simplepoint.plugin.rbac.tenant.service.initialize;

import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.security.SecurityDictionaryCodes;
import org.simplepoint.security.entity.ResourceScopeType;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Registers the authorization model's resource scopes as a reusable system dictionary.
 */
@Component
public class ResourceScopeTypeDictionaryInitializer {

  private static final String INIT_MODULE = "security-resource-scope-type-dictionary";

  /**
   * Registers the resource scope dictionary bootstrap contribution.
   *
   * @param dictionaryService     the dictionary service
   * @param dictionaryItemService the dictionary item service
   * @return the platform bootstrap contribution
   */
  @Bean
  public PlatformBootstrapContribution resourceScopeTypeDictionaryBootstrapContribution(
      final DictionaryService dictionaryService,
      final DictionaryItemService dictionaryItemService
  ) {
    return () -> BootstrapContribution.versioned(
        "rbac-tenant",
        "dictionary",
        INIT_MODULE,
        "1",
        310,
        () -> initializeResourceScopeTypeDictionary(dictionaryService, dictionaryItemService)
    );
  }

  private void initializeResourceScopeTypeDictionary(
      DictionaryService dictionaryService,
      DictionaryItemService dictionaryItemService
  ) {
    var dictionary = BuiltInDictionaryRegistrar.ensureDictionary(
        dictionaryService,
        SecurityDictionaryCodes.RESOURCE_SCOPE_TYPE,
        "资源作用域",
        "用于定义资源支持的授权作用域",
        40
    );
    for (ResourceScopeType scopeType : ResourceScopeType.values()) {
      String value = scopeType.name();
      BuiltInDictionaryRegistrar.ensureItem(
          dictionaryItemService,
          dictionary.getCode(),
          value,
          value,
          "resources.scope." + value,
          "资源授权作用域 " + value,
          (scopeType.ordinal() + 1) * 10
      );
    }
  }
}
