package org.simplepoint.plugin.ai.core.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for AI model definitions.
 */
@Repository
public interface JpaAiModelDefinitionRepository
    extends BaseRepository<AiModelDefinition, String>, AiModelDefinitionRepository {

  @Override
  @Query("""
      select m from AiModelDefinition m
      where m.id = :id and m.deletedAt is null
      """)
  Optional<AiModelDefinition> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select m from AiModelDefinition m
      where m.providerId = :providerId and m.modelId = :modelId and m.deletedAt is null
      """)
  Optional<AiModelDefinition> findActiveByProviderAndModelId(
      @Param("providerId") String providerId,
      @Param("modelId") String modelId
  );

  @Override
  @Query("""
      select m from AiModelDefinition m
      where m.providerId = :providerId and m.deletedAt is null
      """)
  List<AiModelDefinition> findAllActiveByProviderId(@Param("providerId") String providerId);

  @Override
  @Query("""
      select m from AiModelDefinition m
      where m.deletedAt is null
        and (
          m.scopeType is null
          or m.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM
        )
        and m.enabled = true
        and m.available = true
      order by m.providerId, m.modelId
      """)
  List<AiModelDefinition> findAllAvailableSystemModels();

  @Override
  @Query("""
      select m from AiModelDefinition m
      where m.deletedAt is null
        and m.enabled = true
        and m.available = true
        and (
          m.scopeType is null
          or m.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM
          or (m.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT and m.tenantId = :tenantId)
        )
      order by m.providerId, m.modelId
      """)
  List<AiModelDefinition> findAllAvailableForTenant(@Param("tenantId") String tenantId);
}
