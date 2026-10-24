package org.simplepoint.plugin.rbac.module;

import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.bootstrap.PlatformBootstrapModuleInitializer;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.dictionary.registration.DictionaryRegistrationService;
import org.simplepoint.plugin.auditing.logging.api.service.ResourceGrantLogRemoteService;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.rbac.core.api.service.CredentialAuthenticationService;
import org.simplepoint.plugin.rbac.core.api.service.UserDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.AuditIdentityDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.ManagedTenantResourceService;
import org.simplepoint.plugin.rbac.tenant.api.service.MemberLabelDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantDirectory;
import org.simplepoint.security.context.AuthorizationContextService;
import org.simplepoint.security.service.ResourceRegistrationService;

/** Declares the identity module and its deployment-independent dependencies. */
@ModulePersistence(entityPackages = "org.simplepoint.security.entity")
@ModuleEvents(IdentityEventDeclarations.class)
@ModuleMigrations
@PlatformModule(
    id = "identity",
    packages = {
      "org.simplepoint.plugin.rbac.authentication",
      "org.simplepoint.plugin.rbac.credentials",
      "org.simplepoint.plugin.rbac.module",
      "org.simplepoint.plugin.rbac.core.api",
      "org.simplepoint.plugin.rbac.core.base",
      "org.simplepoint.plugin.rbac.core.service",
      "org.simplepoint.plugin.rbac.resource.api",
      "org.simplepoint.plugin.rbac.resource.base",
      "org.simplepoint.plugin.rbac.resource.service",
      "org.simplepoint.plugin.rbac.tenant.api",
      "org.simplepoint.plugin.rbac.tenant.directory",
      "org.simplepoint.plugin.rbac.tenant.repository",
      "org.simplepoint.plugin.rbac.tenant.service"
    },
    initializers = {PlatformBootstrapModuleInitializer.class, I18nModuleInitializer.class},
    provides = {
      DictionaryRegistrationService.class,
      CredentialAuthenticationService.class,
      UserDirectory.class,
      TenantDirectory.class,
      AuditIdentityDirectory.class,
      MemberLabelDirectory.class,
      ManagedTenantResourceService.class,
      AuthorizationContextService.class,
      ResourceRegistrationService.class
    },
    consumes = {ResourceGrantLogRemoteService.class})
public final class IdentityModule {
  private IdentityModule() {}
}
