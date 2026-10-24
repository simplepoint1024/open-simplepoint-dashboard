package org.simplepoint.plugin.i18n.module;

import org.simplepoint.core.locale.I18nMessageService;
import org.simplepoint.platform.bootstrap.PlatformBootstrapModuleInitializer;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.i18n.api.service.I18nLanguageDirectory;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;

/** Declares the i18n module and its deployment-independent dependencies. */
@ModulePersistence(entityPackages = "org.simplepoint.core.entity")
@ModuleMigrations
@PlatformModule(
    id = "i18n",
    packages = {
      "org.simplepoint.plugin.i18n.api",
      "org.simplepoint.plugin.i18n.initialize",
      "org.simplepoint.plugin.i18n.repository",
      "org.simplepoint.plugin.i18n.service",
      "org.simplepoint.plugin.i18n.module"
    },
    initializers = {PlatformBootstrapModuleInitializer.class, I18nModuleInitializer.class},
    provides = {
      I18nLanguageDirectory.class,
      I18nMessageRegistrationService.class,
      I18nMessageService.class
    })
public final class I18nModule {
  private I18nModule() {}
}
