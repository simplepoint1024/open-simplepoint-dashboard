package org.simplepoint.plugin.common.ui;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleResourceOwner;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.springframework.context.annotation.Configuration;

/** Complete historical Common resource snapshot and its local permission descriptor providers. */
@PlatformModule(
    id = "common-ui",
    packages = "org.simplepoint.plugin.common.ui",
    requires = {
      @ModuleDependency(id = "identity-http"),
      @ModuleDependency(id = "oidc-http"),
      @ModuleDependency(id = "i18n-http"),
      @ModuleDependency(id = "personal-http"),
      @ModuleDependency(id = "notification-http"),
      @ModuleDependency(id = "storage-http")
    },
    consumes = org.simplepoint.core.locale.I18nMessageService.class,
    initializers = {ResourceModuleInitializer.class, I18nModuleInitializer.class})
@ModuleMigrations(ownsSchema = false)
@ModuleResourceOwner("common")
@Configuration(proxyBeanMethods = false)
public class CommonUiModule {
  /** Shared UI translations use this module's declared service identity. */
  @org.springframework.context.annotation.Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(org.simplepoint.platform.composition.ModulePlan.class)
  public org.simplepoint.core.locale.CacheSimpleMessageSource cacheSimpleMessageSource(
      org.simplepoint.core.locale.I18nMessageService messages) {
    return new org.simplepoint.core.locale.CacheSimpleMessageSource(messages);
  }

  /** Retains the platform locale adapter without an unowned RPC client in shared auto-configuration. */
  @org.springframework.context.annotation.Bean
  @org.springframework.context.annotation.Primary
  @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(org.simplepoint.platform.composition.ModulePlan.class)
  public org.simplepoint.core.locale.SimpleMessageSource simpleMessageSource(
      org.simplepoint.core.locale.CacheSimpleMessageSource messages) {
    return new org.simplepoint.core.locale.SimpleMessageSource(messages);
  }
}
