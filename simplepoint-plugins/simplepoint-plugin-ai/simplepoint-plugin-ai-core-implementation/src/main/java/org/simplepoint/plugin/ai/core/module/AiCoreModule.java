package org.simplepoint.plugin.ai.core.module;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Declares the ai-core module and its deployment-independent dependencies. */
@ModuleMigrations
@PlatformModule(
    id = "ai-core",
    packages = {
      "org.simplepoint.plugin.ai.core.api",
      "org.simplepoint.plugin.ai.core.module",
      "org.simplepoint.plugin.ai.core.repository",
      "org.simplepoint.plugin.ai.core.service"
    })
public final class AiCoreModule {
  private AiCoreModule() {}
}
