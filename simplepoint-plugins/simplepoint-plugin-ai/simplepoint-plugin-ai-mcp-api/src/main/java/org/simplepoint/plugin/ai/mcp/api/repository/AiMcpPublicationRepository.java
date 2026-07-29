package org.simplepoint.plugin.ai.mcp.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpPublication;

/**
 * Repository contract for MCP publications.
 */
public interface AiMcpPublicationRepository
    extends BaseRepository<AiMcpPublication, String> {

  /**
   * Finds one non-deleted publication by identifier.
   */
  Optional<AiMcpPublication> findActiveById(String id);

  /**
   * Finds one non-deleted publication by its globally stable endpoint code.
   */
  Optional<AiMcpPublication> findActiveByCode(String code);

  /**
   * Finds non-deleted publications backed by one upstream server.
   */
  List<AiMcpPublication> findActiveByUpstreamServerId(String upstreamServerId);

  /**
   * Finds one non-deleted publication within a management scope.
   */
  Optional<AiMcpPublication> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );
}
