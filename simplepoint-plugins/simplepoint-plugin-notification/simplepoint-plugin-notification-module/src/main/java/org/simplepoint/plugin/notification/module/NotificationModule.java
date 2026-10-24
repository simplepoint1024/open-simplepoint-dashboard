package org.simplepoint.plugin.notification.module;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.dictionary.ModuleDictionaries;
import org.simplepoint.platform.dictionary.registration.DictionaryModuleInitializer;
import org.simplepoint.plugin.notification.api.initialization.NotificationDictionaries;
import org.simplepoint.plugin.notification.api.service.NotificationDeliveryService;

/** Declares the notification module and its deployment-independent dependencies. */
@ModuleMigrations
@ModuleDictionaries(NotificationDictionaries.class)
@PlatformModule(
    id = "notification",
    packages = {
      "org.simplepoint.plugin.notification.api",
      "org.simplepoint.plugin.notification.service",
      "org.simplepoint.plugin.notification.repository",
      "org.simplepoint.plugin.notification.module"
    },
    requires = {@ModuleDependency(id = "personal")},
    initializers = DictionaryModuleInitializer.class,
    provides = {NotificationDeliveryService.class})
public final class NotificationModule {
  private NotificationModule() {}
}
