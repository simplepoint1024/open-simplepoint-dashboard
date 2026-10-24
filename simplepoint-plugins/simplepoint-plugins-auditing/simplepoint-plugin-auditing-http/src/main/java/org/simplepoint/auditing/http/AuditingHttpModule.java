package org.simplepoint.auditing.http;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleResourceOwner;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Optional monitoring and audit management endpoints, independent of the runnable service. */
@PlatformModule(
    id = "auditing-http",
    packages = {
      "org.simplepoint.auditing.http", "org.simplepoint.plugin.auditing.logging.rest",
      "org.simplepoint.plugin.auditing.ratelimit.rest", "org.simplepoint.plugin.auditing.redis.rest"
    },
    requires = {
      @ModuleDependency(id = "audit-logging"),
      @ModuleDependency(id = "audit-rate-limit"),
      @ModuleDependency(id = "audit-redis")
    },
    initializers = {
      ModuleAuthorizationInitializer.class,
      ResourceModuleInitializer.class,
      I18nModuleInitializer.class
    })
@ModuleHttp(namespace = "/auditing")
@ModuleMigrations(ownsSchema = false)
@ModuleResourceOwner("auditing")
@Configuration(proxyBeanMethods = false)
public class AuditingHttpModule {}
