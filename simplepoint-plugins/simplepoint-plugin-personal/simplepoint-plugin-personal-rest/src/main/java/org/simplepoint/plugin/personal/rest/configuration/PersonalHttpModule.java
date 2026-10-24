package org.simplepoint.plugin.personal.rest.configuration;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Optional browser adapter; data and business transactions remain in the owning domain. */
@PlatformModule(
    initializers = ModuleAuthorizationInitializer.class,
    id = "personal-http",
    packages = "org.simplepoint.plugin.personal.rest",
    requires = @ModuleDependency(id = "personal"))
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class PersonalHttpModule {}
