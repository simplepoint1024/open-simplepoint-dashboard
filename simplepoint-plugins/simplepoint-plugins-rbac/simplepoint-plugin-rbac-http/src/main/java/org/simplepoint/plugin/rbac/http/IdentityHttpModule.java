package org.simplepoint.plugin.rbac.http;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Browser adapter for the shared identity, tenancy and resource-authorization domain. */
@PlatformModule(
    initializers = ModuleAuthorizationInitializer.class,
    id = "identity-http",
    packages = {
      "org.simplepoint.plugin.rbac.http",
      "org.simplepoint.plugin.rbac.core.rest",
      "org.simplepoint.plugin.rbac.tenant.rest",
      "org.simplepoint.plugin.rbac.resource.rest"
    },
    requires = @ModuleDependency(id = "identity"))
@ModuleHttp(namespace = "/common", routes = ControllerHttpRoutes.class)
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class IdentityHttpModule {}
