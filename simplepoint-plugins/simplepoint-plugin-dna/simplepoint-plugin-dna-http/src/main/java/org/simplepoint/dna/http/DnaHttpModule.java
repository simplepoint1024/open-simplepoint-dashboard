package org.simplepoint.dna.http;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleResourceOwner;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Optional DNA browser adapters; domain modules retain storage, SQL and publication ownership. */
@PlatformModule(
    id = "dna-http",
    packages = {
      "org.simplepoint.dna.http", "org.simplepoint.dna.model.controller",
      "org.simplepoint.dna.runtime.controller", "org.simplepoint.plugin.dna.core.rest",
      "org.simplepoint.plugin.dna.federation.rest"
    },
    requires = @ModuleDependency(id = "dna-runtime"),
    initializers = {
      ModuleAuthorizationInitializer.class,
      ResourceModuleInitializer.class,
      I18nModuleInitializer.class
    })
@ModuleHttp(namespace = "/dna")
@ModuleMigrations(ownsSchema = false)
@ModuleResourceOwner("dna")
@Configuration(proxyBeanMethods = false)
public class DnaHttpModule {}
