package org.simplepoint.plugin.oidc.module;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.platform.dictionary.ModuleDictionaries;
import org.simplepoint.platform.dictionary.registration.DictionaryModuleInitializer;
import org.simplepoint.plugin.i18n.api.initialization.I18nModuleInitializer;
import org.simplepoint.plugin.oidc.api.initialization.ExternalIdentityProviderDictionaries;

/** Declares the oidc module and its deployment-independent dependencies. */
@ModuleMigrations
@ModuleDictionaries(ExternalIdentityProviderDictionaries.class)
@PlatformModule(
    id = "oidc",
    packages = {
      "org.simplepoint.plugin.oidc.api",
      "org.simplepoint.plugin.oidc.service",
      "org.simplepoint.plugin.oidc.module"
    },
    initializers = {I18nModuleInitializer.class, DictionaryModuleInitializer.class})
public final class OidcModule {
  private OidcModule() {}
}
