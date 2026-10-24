package org.simplepoint.plugin.storage.http;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Optional upload/download and administration adapters for the storage domain. */
@PlatformModule(
    initializers = ModuleAuthorizationInitializer.class,
    id = "storage-http",
    packages = {"org.simplepoint.plugin.storage.http", "org.simplepoint.plugin.storage.rest"},
    requires = @ModuleDependency(id = "storage"),
    consumes = ObjectStorageSourceService.class)
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class StorageHttpModule {}
