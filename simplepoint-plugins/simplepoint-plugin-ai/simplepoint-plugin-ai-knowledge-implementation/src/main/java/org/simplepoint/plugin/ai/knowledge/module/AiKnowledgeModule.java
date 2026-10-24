package org.simplepoint.plugin.ai.knowledge.module;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePersistence;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.plugin.storage.api.service.ObjectStorageSourceService;

/** Declares the ai-knowledge module and its deployment-independent dependencies. */
@ModulePersistence(sameStoreAs = "ai-core")
@ModuleMigrations
@PlatformModule(
    id = "ai-knowledge",
    packages = {
      "org.simplepoint.plugin.ai.knowledge.api",
      "org.simplepoint.plugin.ai.knowledge.module",
      "org.simplepoint.plugin.ai.knowledge.repository",
      "org.simplepoint.plugin.ai.knowledge.service"
    },
    requires = {@ModuleDependency(id = "ai-core")},
    consumes = {ObjectStorageSourceService.class})
public final class AiKnowledgeModule {
  private AiKnowledgeModule() {}
}
