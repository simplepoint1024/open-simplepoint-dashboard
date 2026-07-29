package org.simplepoint.plugin.ai.runtime.api.service;

import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpEndpoint;

/**
 * Resolves and activates the managed OCI replica serving one MCP registration.
 */
public interface AiRuntimeMcpEndpointService {

  /**
   * Returns a fenced Runtime-node Streamable HTTP bridge endpoint.
   */
  default RuntimeMcpEndpoint resolve(
      String serverId,
      AiResourceScope scopeType,
      String tenantId
  ) {
    return resolve(serverId, scopeType, tenantId, null);
  }

  /**
   * Returns a fenced Runtime endpoint bound to a hashed MCP session directory entry.
   */
  RuntimeMcpEndpoint resolve(
      String serverId,
      AiResourceScope scopeType,
      String tenantId,
      String sessionId
  );

  /**
   * Removes a matching session assignment and temporarily quarantines a failed replica.
   */
  void invalidate(
      String serverId,
      AiResourceScope scopeType,
      String tenantId,
      String sessionId,
      RuntimeMcpEndpoint endpoint
  );
}
