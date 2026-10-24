package org.simplepoint.composition.fixture.startupduplicate;

/** Fixture with a distinct owner that incorrectly claims another module's resource code. */
@org.simplepoint.platform.composition.PlatformModule(
    id = "startup-duplicate",
    packages = "org.simplepoint.composition.fixture.startupduplicate",
    initializers =
        org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer.class)
@org.simplepoint.platform.composition.ModuleResourceOwner("historic-duplicate")
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class StartupDuplicateModule {}
