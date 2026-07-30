package org.simplepoint.plugin.ai.agent.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentDefinitionRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for Agent definitions.
 */
@Repository
public interface JpaAiAgentDefinitionRepository
    extends BaseRepository<AiAgentDefinition, String>,
    AiAgentDefinitionRepository {

  @Override
  @Query("""
      select agent from AiAgentDefinition agent
      where agent.id = :id and agent.deletedAt is null
      """)
  Optional<AiAgentDefinition> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select agent from AiAgentDefinition agent
      where agent.id = :id and agent.deletedAt is null
      """)
  Optional<AiAgentDefinition> findActiveByIdForUpdate(@Param("id") String id);

  @Override
  @Query("""
      select agent from AiAgentDefinition agent
      where agent.code = :code
        and agent.scopeType = :scopeType
        and ((:tenantId is null and agent.tenantId is null)
          or agent.tenantId = :tenantId)
        and agent.deletedAt is null
      """)
  Optional<AiAgentDefinition> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );

  @Override
  @Query("""
      select agent from AiAgentDefinition agent
      where agent.scopeType = :scopeType
        and ((:tenantId is null and agent.tenantId is null)
          or agent.tenantId = :tenantId)
        and agent.deletedAt is null
      """)
  Page<AiAgentDefinition> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );
}
