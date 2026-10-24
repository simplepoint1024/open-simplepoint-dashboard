package org.simplepoint.platform.bootstrap.registry;

import org.simplepoint.platform.bootstrap.service.PlatformContributionService;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;

/** Owns the durable registry for repeatable platform initialization. */
@ModulePersistence(entityPackages = "org.simplepoint.platform.bootstrap.entity")
@ModuleMigrations
@PlatformModule(
    id = "bootstrap-registry",
    packages = "org.simplepoint.platform.bootstrap.registry",
    provides = PlatformContributionService.class)
public final class BootstrapRegistryModule {
  private BootstrapRegistryModule() {}
}
