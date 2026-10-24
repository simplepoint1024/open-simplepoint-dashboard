package org.simplepoint.composition.fixture.startuptwo;

import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;

/** Startup fixture without business RPC consumption declarations. */
@org.simplepoint.platform.composition.ModuleResourceOwner("startup-two")
@PlatformModule(
    id = "startup-two",
    packages = "org.simplepoint.composition.fixture.startuptwo",
    initializers = {ResourceModuleInitializer.class, I18nModuleInitializer.class})
public final class StartupTwoModule {}
