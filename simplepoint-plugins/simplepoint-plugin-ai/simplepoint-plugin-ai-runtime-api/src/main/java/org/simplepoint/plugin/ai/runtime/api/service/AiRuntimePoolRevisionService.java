package org.simplepoint.plugin.ai.runtime.api.service;

import java.util.List;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolRevisionRequest;

/** Activates immutable MCP revisions on existing Runtime Pools. */
public interface AiRuntimePoolRevisionService {

  /** Binds one managed pool to an immutable published revision. */
  AiRuntimePool bind(String poolId, RuntimePoolRevisionRequest request);

  /** Rolls all pools already attached to a profile to the selected revision. */
  List<AiRuntimePool> activateAttached(String profileId, String revisionId);
}
