package org.simplepoint.plugin.ai.runtime.api.service;

import java.util.List;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeSecretFile;

/** Resolves subject-owned, deferred secrets at immediate workload dispatch. */
public interface AiRuntimeSubjectSecretResolver {

  /** Returns deferred bindings owned by the managed MCP server's subject. */
  List<RuntimeSecretFile> resolve(
      String serverId,
      AiResourceScope scopeType,
      String tenantId,
      List<RuntimeMcpProfileSpec.SecretBinding> bindings
  );
}
