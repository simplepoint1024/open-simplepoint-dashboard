package org.simplepoint.plugin.dna.federation.module;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.rbac.core.api.service.CredentialAuthenticationService;
import org.simplepoint.plugin.rbac.core.api.service.UserDirectory;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantDirectory;
import org.simplepoint.security.context.AuthorizationContextService;

/** Declares the dna-federation module and its deployment-independent dependencies. */
@ModulePersistence(sameStoreAs = "dna-core")
@ModuleMigrations
@PlatformModule(
    id = "dna-federation",
    packages = {
      "org.simplepoint.plugin.dna.federation.api",
      "org.simplepoint.plugin.dna.federation.entity",
      "org.simplepoint.plugin.dna.federation.module",
      "org.simplepoint.plugin.dna.federation.repository",
      "org.simplepoint.plugin.dna.federation.service",
      "org.simplepoint.dna.model.service"
    },
    requires = {@ModuleDependency(id = "dna-core")},
    consumes = {
      CredentialAuthenticationService.class,
      UserDirectory.class,
      TenantDirectory.class,
      AuthorizationContextService.class
    })
public final class DnaFederationModule {
  private DnaFederationModule() {}
}
