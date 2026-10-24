package org.simplepoint.ai.http;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleResourceOwner;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Selected browser, inference and node-control transports for the AI domains. */
@PlatformModule(
    id = "ai-http",
    packages = {
      "org.simplepoint.ai.http",
      "org.simplepoint.plugin.ai.core.rest",
      "org.simplepoint.plugin.ai.catalog.rest",
      "org.simplepoint.plugin.ai.knowledge.rest",
      "org.simplepoint.plugin.ai.mcp.rest",
      "org.simplepoint.plugin.ai.runtime.rest"
    },
    requires = {@ModuleDependency(id = "ai-catalog"), @ModuleDependency(id = "ai-knowledge")},
    initializers = {
      ModuleAuthorizationInitializer.class,
      ResourceModuleInitializer.class,
      I18nModuleInitializer.class
    })
@ModuleHttp(namespace = "/ai")
@ModuleMigrations(ownsSchema = false)
@ModuleResourceOwner("ai")
@Configuration(proxyBeanMethods = false)
public class AiHttpModule {}
