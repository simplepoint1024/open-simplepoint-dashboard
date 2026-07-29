package org.simplepoint.plugin.ai.mcp.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpPublication;

/**
 * Management service for externally exposed MCP publications.
 */
public interface AiMcpPublicationService extends BaseService<AiMcpPublication, String> {

  /**
   * Finds one active publication visible in the current management scope.
   */
  Optional<AiMcpPublication> findActiveById(String id);
}
