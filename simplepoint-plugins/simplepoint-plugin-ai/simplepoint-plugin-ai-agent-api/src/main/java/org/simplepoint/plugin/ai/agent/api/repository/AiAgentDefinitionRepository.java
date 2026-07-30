package org.simplepoint.plugin.ai.agent.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for scope-owned Agent definitions.
 */
public interface AiAgentDefinitionRepository
    extends BaseRepository<AiAgentDefinition, String> {

  /**
   * Finds a non-deleted Agent by identifier.
   */
  Optional<AiAgentDefinition> findActiveById(String id);

  /**
   * Finds and locks a non-deleted Agent by identifier.
   */
  Optional<AiAgentDefinition> findActiveByIdForUpdate(String id);

  /**
   * Finds an Agent code in one ownership scope.
   */
  Optional<AiAgentDefinition> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );

  /**
   * Pages non-deleted Agents in one ownership scope.
   */
  Page<AiAgentDefinition> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );
}
