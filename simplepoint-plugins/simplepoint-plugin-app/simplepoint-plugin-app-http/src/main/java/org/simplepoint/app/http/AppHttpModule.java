package org.simplepoint.app.http;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleResourceOwner;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Optional APP browser endpoints; transactions and persistence belong to app-form. */
@PlatformModule(
    id = "app-http",
    packages = {
      "org.simplepoint.app.http",
      "org.simplepoint.app.catalog.controller",
      "org.simplepoint.app.form.controller"
    },
    requires = @ModuleDependency(id = "app-form"),
    initializers = {
      ModuleAuthorizationInitializer.class,
      ResourceModuleInitializer.class,
      I18nModuleInitializer.class
    })
@ModuleHttp(namespace = "/app")
@ModuleMigrations(ownsSchema = false)
@ModuleResourceOwner("app")
@Configuration(proxyBeanMethods = false)
public class AppHttpModule {}
