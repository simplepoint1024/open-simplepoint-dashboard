package org.simplepoint.plugin.storage.module;

import org.simplepoint.platform.bootstrap.PlatformBootstrapModuleInitializer;
import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;

/** Declares the storage module and its deployment-independent dependencies. */
@ModulePersistence(sameStoreAs = "identity")
@ModuleMigrations
@PlatformModule(
    id = "storage",
    packages = {
      "org.simplepoint.plugin.storage.api",
      "org.simplepoint.plugin.storage.entity",
      "org.simplepoint.plugin.storage.module",
      "org.simplepoint.plugin.storage.repository",
      "org.simplepoint.plugin.storage.s3",
      "org.simplepoint.plugin.storage.service"
    },
    initializers = {PlatformBootstrapModuleInitializer.class, I18nModuleInitializer.class},
    requires = @ModuleDependency(id = "identity"),
    provides = {ObjectStorageSourceService.class})
public final class StorageModule {
  private StorageModule() {}
}
