package org.simplepoint.common.server.initialize;

import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.oidc.api.initialization.ExternalIdentityProviderDictionaries;
import org.simplepoint.plugin.rbac.tenant.service.initialize.BuiltInDictionaryRegistrar;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryDeclarationWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Legacy Common bootstrap adapter; dictionary metadata belongs to its logical domain. */
@Component
public class ExternalIdentityProviderDictionaryInitializer {
  /** Retains the existing bootstrap identity and delegates application to identity persistence. */
  @Bean
  public PlatformBootstrapContribution externalIdentityProviderDictionaryContribution(
      DictionaryDeclarationWriter writer) {
    return () ->
        BuiltInDictionaryRegistrar.contribution(
            new ExternalIdentityProviderDictionaries().declaration(), writer::apply);
  }
}
