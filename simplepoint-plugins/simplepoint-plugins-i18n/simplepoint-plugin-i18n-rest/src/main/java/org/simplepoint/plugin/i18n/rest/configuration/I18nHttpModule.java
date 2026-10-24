package org.simplepoint.plugin.i18n.rest.configuration;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Optional browser adapter; the owning domain retains its persistence and initialization. */
@PlatformModule(
    initializers = ModuleAuthorizationInitializer.class,
    id = "i18n-http",
    packages = "org.simplepoint.plugin.i18n.rest",
    requires = @ModuleDependency(id = "i18n"))
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class I18nHttpModule {}
