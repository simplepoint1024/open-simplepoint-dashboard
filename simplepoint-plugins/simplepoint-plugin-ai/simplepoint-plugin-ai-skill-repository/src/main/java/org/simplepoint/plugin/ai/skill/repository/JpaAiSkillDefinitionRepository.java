package org.simplepoint.plugin.ai.skill.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for Skill definitions.
 */
@Repository
public interface JpaAiSkillDefinitionRepository
    extends BaseRepository<AiSkillDefinition, String>,
    AiSkillDefinitionRepository {

  @Override
  @Query("""
      select skill from AiSkillDefinition skill
      where skill.id = :id and skill.deletedAt is null
      """)
  Optional<AiSkillDefinition> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select skill from AiSkillDefinition skill
      where skill.id = :id and skill.deletedAt is null
      """)
  Optional<AiSkillDefinition> findActiveByIdForUpdate(@Param("id") String id);

  @Override
  @Query("""
      select skill from AiSkillDefinition skill
      where skill.code = :code
        and skill.scopeType = :scopeType
        and ((:tenantId is null and skill.tenantId is null)
          or skill.tenantId = :tenantId)
        and skill.deletedAt is null
      """)
  Optional<AiSkillDefinition> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );

  @Override
  @Query("""
      select skill from AiSkillDefinition skill
      where skill.scopeType = :scopeType
        and ((:tenantId is null and skill.tenantId is null)
          or skill.tenantId = :tenantId)
        and skill.deletedAt is null
      """)
  Page<AiSkillDefinition> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );
}
