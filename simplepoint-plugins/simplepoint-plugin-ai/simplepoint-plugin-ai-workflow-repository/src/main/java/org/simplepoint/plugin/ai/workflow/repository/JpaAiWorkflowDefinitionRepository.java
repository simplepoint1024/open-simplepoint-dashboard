package org.simplepoint.plugin.ai.workflow.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDefinition;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA Workflow definition repository.
 */
@Repository
public interface JpaAiWorkflowDefinitionRepository
    extends BaseRepository<AiWorkflowDefinition, String>,
    AiWorkflowDefinitionRepository {

  @Override
  @Query("""
      select workflow from AiWorkflowDefinition workflow
      where workflow.id = :id and workflow.deletedAt is null
      """)
  Optional<AiWorkflowDefinition> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select workflow from AiWorkflowDefinition workflow
      where workflow.id = :id and workflow.deletedAt is null
      """)
  Optional<AiWorkflowDefinition> findActiveByIdForUpdate(
      @Param("id") String id
  );

  @Override
  @Query("""
      select workflow from AiWorkflowDefinition workflow
      where workflow.code = :code
        and workflow.scopeType = :scopeType
        and ((:tenantId is null and workflow.tenantId is null)
          or workflow.tenantId = :tenantId)
        and workflow.deletedAt is null
      """)
  Optional<AiWorkflowDefinition> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );

  @Override
  @Query("""
      select workflow from AiWorkflowDefinition workflow
      where workflow.scopeType = :scopeType
        and ((:tenantId is null and workflow.tenantId is null)
          or workflow.tenantId = :tenantId)
        and workflow.deletedAt is null
      order by workflow.createdAt desc
      """)
  Page<AiWorkflowDefinition> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );
}
