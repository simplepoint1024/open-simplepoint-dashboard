package org.simplepoint.plugin.dna.core.configuration;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Owns JDBC source/driver management and the local dialect infrastructure. */
@ModuleMigrations
@PlatformModule(
    id = "dna-core",
    packages = {
      "org.simplepoint.plugin.dna.core.api",
      "org.simplepoint.plugin.dna.core.configuration",
      "org.simplepoint.plugin.dna.core.repository",
      "org.simplepoint.plugin.dna.core.service"
    })
public final class DnaCoreModule {
  private DnaCoreModule() {}
}
