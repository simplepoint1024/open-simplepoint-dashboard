package org.simplepoint.cloud.oauth.server.module;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Configuration;

/** Selected authorization protocol/browser adapter over the owning identity and OIDC domains. */
@PlatformModule(
    id = "authorization-http",
    packages = "org.simplepoint.cloud.oauth.server",
    requires = {
      @ModuleDependency(id = "identity"),
      @ModuleDependency(id = "oidc"),
      @ModuleDependency(id = "authorization-audit")
    })
@ModuleHttp(namespace = "/", scopedAuthorization = false, routes = AuthorizationHttpRoutes.class)
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class AuthorizationHttpModule {}
