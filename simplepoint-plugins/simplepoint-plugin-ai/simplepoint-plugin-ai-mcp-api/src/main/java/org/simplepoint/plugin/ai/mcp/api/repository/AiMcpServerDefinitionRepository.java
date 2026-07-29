package org.simplepoint.plugin.ai.mcp.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;

/**
 * Repository contract for MCP server registrations.
 */
public interface AiMcpServerDefinitionRepository
    extends BaseRepository<AiMcpServerDefinition, String> {

  /**
   * Finds a non-deleted server by identifier.
   *
   * @param id server identifier
   * @return active server
   */
  Optional<AiMcpServerDefinition> findActiveById(String id);

  /**
   * Finds a non-deleted server code in one ownership scope.
   *
   * @param code server code
   * @param scopeType platform or tenant scope
   * @param tenantId tenant identifier for tenant scope
   * @return matching active server
   */
  Optional<AiMcpServerDefinition> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );
}
