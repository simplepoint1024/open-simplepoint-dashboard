package org.simplepoint.plugin.ai.mcp.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpCatalogRegistration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for MCP server registrations.
 */
public interface AiMcpServerDefinitionRepository
    extends BaseRepository<AiMcpServerDefinition, String> {

  /** Searches active catalog metadata in one exact ownership scope before SQL count and paging. */
  Page<McpCatalogRegistration> findCatalogInScope(
      AiResourceScope scopeType, String tenantId, String keyword, Pageable pageable
  );

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
