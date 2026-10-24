package org.simplepoint.workflow.http;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleResourceOwner;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.simplepoint.security.oauth2.resourceserver.composition.ModuleAuthorizationInitializer;
import org.springframework.context.annotation.Configuration;

/** Optional browser API adapter; domain implementation and storage remain in workflow. */
@PlatformModule(
    id = "workflow-http",
    packages = {"org.simplepoint.workflow.http", "org.simplepoint.workflow.rest"},
    requires = @ModuleDependency(id = "workflow"),
    initializers = {
      ModuleAuthorizationInitializer.class,
      ResourceModuleInitializer.class,
      I18nModuleInitializer.class
    })
@ModuleHttp(namespace = "/workflow")
@ModuleMigrations(ownsSchema = false)
@ModuleResourceOwner("workflow")
@Configuration(proxyBeanMethods = false)
public class WorkflowHttpModule {}
