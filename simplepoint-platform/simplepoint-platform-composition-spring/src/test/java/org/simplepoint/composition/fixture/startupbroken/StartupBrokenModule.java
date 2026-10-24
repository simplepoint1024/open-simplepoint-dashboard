package org.simplepoint.composition.fixture.startupbroken;

import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;

/** Startup fixture without business RPC consumption declarations. */
@org.simplepoint.platform.composition.ModuleResourceOwner("startup-zbroken")
@PlatformModule(
    id = "startup-zbroken",
    packages = "org.simplepoint.composition.fixture.startupbroken",
    initializers = {ResourceModuleInitializer.class, I18nModuleInitializer.class})
public final class StartupBrokenModule {}
