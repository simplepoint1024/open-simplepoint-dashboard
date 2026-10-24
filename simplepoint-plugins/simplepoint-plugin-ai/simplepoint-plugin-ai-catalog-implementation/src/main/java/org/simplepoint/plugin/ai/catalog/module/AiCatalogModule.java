package org.simplepoint.plugin.ai.catalog.module;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;

/** Declares the ai-catalog module and its deployment-independent dependencies. */
@ModulePersistence(sameStoreAs = "ai-mcp")
@ModuleMigrations
@PlatformModule(
    id = "ai-catalog",
    packages = {
      "org.simplepoint.plugin.ai.catalog.api",
      "org.simplepoint.plugin.ai.catalog.entity",
      "org.simplepoint.plugin.ai.catalog.module",
      "org.simplepoint.plugin.ai.catalog.repository",
      "org.simplepoint.plugin.ai.catalog.service"
    },
    requires = {
      @ModuleDependency(id = "ai-core"),
      @ModuleDependency(id = "ai-runtime"),
      @ModuleDependency(id = "ai-mcp")
    })
public final class AiCatalogModule {
  private AiCatalogModule() {}
}
