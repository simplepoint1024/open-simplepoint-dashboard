package org.simplepoint.plugin.oidc.rest.configuration;

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
    id = "oidc-http",
    packages = "org.simplepoint.plugin.oidc.rest",
    requires = @ModuleDependency(id = "oidc"))
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class OidcHttpModule {}
