package org.simplepoint.plugin.ai.runtime.module;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Declares the ai-runtime module and its deployment-independent dependencies. */
@ModuleMigrations
@PlatformModule(
    id = "ai-runtime",
    packages = {
      "org.simplepoint.plugin.ai.runtime.api",
      "org.simplepoint.plugin.ai.runtime.module",
      "org.simplepoint.plugin.ai.runtime.repository",
      "org.simplepoint.plugin.ai.runtime.service"
    },
    requires = {@ModuleDependency(id = "ai-core")})
public final class AiRuntimeModule {
  private AiRuntimeModule() {}
}
