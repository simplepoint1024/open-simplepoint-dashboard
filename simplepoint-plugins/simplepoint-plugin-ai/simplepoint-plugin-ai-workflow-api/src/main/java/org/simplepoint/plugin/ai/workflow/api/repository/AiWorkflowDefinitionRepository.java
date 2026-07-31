package org.simplepoint.plugin.ai.workflow.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Persistence contract for Workflow definitions.
 */
public interface AiWorkflowDefinitionRepository
    extends BaseRepository<AiWorkflowDefinition, String> {

  /**
   * Finds a non-deleted Workflow by ID.
   */
  Optional<AiWorkflowDefinition> findActiveById(String id);

  /**
   * Locks and finds a non-deleted Workflow by ID.
   */
  Optional<AiWorkflowDefinition> findActiveByIdForUpdate(String id);

  /**
   * Finds a non-deleted Workflow by code and exact scope.
   */
  Optional<AiWorkflowDefinition> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );

  /**
   * Pages non-deleted Workflows visible in an exact scope.
   */
  Page<AiWorkflowDefinition> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );
}
