package org.simplepoint.plugin.ai.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyOptionView;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyResolutionView;
import org.simplepoint.plugin.ai.core.api.repository.AiModelDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
      where m.id in :ids and m.deletedAt is null
      order by m.id
      """)
  List<AiModelDefinition> findAllActiveByIdIn(@Param("ids") Collection<String> ids);

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

  @Override
  @Query(
      value = """
          select
            model.id as resourceId,
            model.modelId as resourceCode,
            coalesce(nullif(model.displayName, ''), model.modelId)
              as resourceName,
            null as resourceVersionId,
            null as resourceVersion,
            model.scopeType as scopeType,
            null as publishedAt
          from AiModelDefinition model
          where model.deletedAt is null
            and model.enabled = true
            and model.available = true
            and model.modelType in (
              org.simplepoint.plugin.ai.core.api.model.AiModelType.LLM,
              org.simplepoint.plugin.ai.core.api.model.AiModelType.MULTIMODAL
            )
            and (
              (
                :tenantOwner = false
                and (model.scopeType is null or model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and model.tenantId is null
              )
              or (
                :tenantOwner = true
                and (
                  (
                    (model.scopeType is null or model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and model.tenantId is null
                  )
                  or (
                    model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and model.tenantId = :ownerTenantId
                  )
                )
              )
            )
            and (
              lower(model.modelId) like :searchPattern escape '!'
              or lower(coalesce(model.displayName, ''))
                like :searchPattern escape '!'
            )
          order by
            lower(coalesce(nullif(model.displayName, ''), model.modelId)),
            lower(model.modelId),
            model.id
          """,
      countQuery = """
          select count(model)
          from AiModelDefinition model
          where model.deletedAt is null
            and model.enabled = true
            and model.available = true
            and model.modelType in (
              org.simplepoint.plugin.ai.core.api.model.AiModelType.LLM,
              org.simplepoint.plugin.ai.core.api.model.AiModelType.MULTIMODAL
            )
            and (
              (
                :tenantOwner = false
                and (model.scopeType is null or model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and model.tenantId is null
              )
              or (
                :tenantOwner = true
                and (
                  (
                    (model.scopeType is null or model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and model.tenantId is null
                  )
                  or (
                    model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and model.tenantId = :ownerTenantId
                  )
                )
              )
            )
            and (
              lower(model.modelId) like :searchPattern escape '!'
              or lower(coalesce(model.displayName, ''))
                like :searchPattern escape '!'
            )
          """
  )
  Page<AiDependencyOptionView> searchDependencyOptions(
      @Param("tenantOwner") boolean tenantOwner,
      @Param("ownerTenantId") String ownerTenantId,
      @Param("searchPattern") String searchPattern,
      Pageable pageable
  );

  @Override
  @Query("""
      select
        model.id as resourceId,
        model.modelId as resourceCode,
        coalesce(nullif(model.displayName, ''), model.modelId)
          as resourceName,
        null as resourceVersionId,
        null as resourceVersion,
        model.scopeType as scopeType,
        null as publishedAt,
        case
          when model.enabled = true
            and model.available = true
            and model.modelType in (
              org.simplepoint.plugin.ai.core.api.model.AiModelType.LLM,
              org.simplepoint.plugin.ai.core.api.model.AiModelType.MULTIMODAL
            )
            then true
          else false
        end as selectable,
        case
          when model.enabled is null or model.enabled = false
            then 'DEPENDENCY_DISABLED'
          when model.available is null
            or model.available = false
            or model.modelType is null
            or model.modelType not in (
              org.simplepoint.plugin.ai.core.api.model.AiModelType.LLM,
              org.simplepoint.plugin.ai.core.api.model.AiModelType.MULTIMODAL
            )
            then 'MODEL_UNAVAILABLE'
          else null
        end as availabilityCode
      from AiModelDefinition model
      where model.deletedAt is null
        and model.id in :resourceIds
        and (
          (
            :tenantOwner = false
            and (model.scopeType is null or model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
            and model.tenantId is null
          )
          or (
            :tenantOwner = true
            and (
              (
                (model.scopeType is null or model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and model.tenantId is null
              )
              or (
                model.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                and model.tenantId = :ownerTenantId
              )
            )
          )
        )
      order by
        lower(coalesce(nullif(model.displayName, ''), model.modelId)),
        lower(model.modelId),
        model.id
      """)
  List<AiDependencyResolutionView> resolveDependencyOptions(
      @Param("tenantOwner") boolean tenantOwner,
      @Param("ownerTenantId") String ownerTenantId,
      @Param("resourceIds") Collection<String> resourceIds
  );
}
