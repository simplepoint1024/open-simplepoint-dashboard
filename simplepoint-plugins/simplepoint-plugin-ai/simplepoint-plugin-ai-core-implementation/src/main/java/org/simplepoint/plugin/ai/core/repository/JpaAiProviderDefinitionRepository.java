package org.simplepoint.plugin.ai.core.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiProviderDefinitionRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for AI provider configurations.
 */
@Repository
public interface JpaAiProviderDefinitionRepository
    extends BaseRepository<AiProviderDefinition, String>, AiProviderDefinitionRepository {

  @Override
  @Query("""
      select p from AiProviderDefinition p
      where p.id = :id and p.deletedAt is null
      """)
  Optional<AiProviderDefinition> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select p from AiProviderDefinition p
      where p.code = :code
        and (
          p.scopeType = :scopeType
          or (p.scopeType is null and :scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
        )
        and ((:tenantId is null and p.tenantId is null) or p.tenantId = :tenantId)
        and p.deletedAt is null
      """)
  Optional<AiProviderDefinition> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );

  @Override
  @Query("""
      select p from AiProviderDefinition p
      where p.deletedAt is null and p.enabled = true and p.autoSyncEnabled = true
      """)
  List<AiProviderDefinition> findAllAutoSyncEnabled();
}
