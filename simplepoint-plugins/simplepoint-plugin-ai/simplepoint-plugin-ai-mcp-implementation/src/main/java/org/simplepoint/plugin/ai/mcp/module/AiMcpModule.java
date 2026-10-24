package org.simplepoint.plugin.ai.mcp.module;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;

/** Declares the ai-mcp module and its deployment-independent dependencies. */
@ModulePersistence(sameStoreAs = "ai-runtime")
@ModuleMigrations
@PlatformModule(
    id = "ai-mcp",
    packages = {
      "org.simplepoint.plugin.ai.mcp.api",
      "org.simplepoint.plugin.ai.mcp.module",
      "org.simplepoint.plugin.ai.mcp.repository",
      "org.simplepoint.plugin.ai.mcp.service"
    },
    requires = {@ModuleDependency(id = "ai-core"), @ModuleDependency(id = "ai-runtime")})
public final class AiMcpModule {
  private AiMcpModule() {}
}
