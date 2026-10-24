package org.simplepoint.plugin.personal.module;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Declares the personal module and its deployment-independent dependencies. */
@ModuleMigrations
@PlatformModule(
    id = "personal",
    packages = {
      "org.simplepoint.plugin.personal.api",
      "org.simplepoint.plugin.personal.service",
      "org.simplepoint.plugin.personal.repository",
      "org.simplepoint.plugin.personal.module"
    })
public final class PersonalModule {
  private PersonalModule() {}
}
