package org.simplepoint.plugin.ai.agent.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyOptionView;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyResolutionView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for immutable Agent versions.
 */
@Repository
public interface JpaAiAgentVersionRepository
    extends BaseRepository<AiAgentVersion, String>,
    AiAgentVersionRepository {

  @Override
  @Query("""
      select version from AiAgentVersion version
      where version.id = :id and version.deletedAt is null
      """)
  Optional<AiAgentVersion> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select version from AiAgentVersion version
      where version.id = :id
        and version.agentId = :agentId
        and version.deletedAt is null
      """)
  Optional<AiAgentVersion> findActiveByIdAndAgentId(
      @Param("id") String id,
      @Param("agentId") String agentId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select version from AiAgentVersion version
      where version.id = :id
        and version.agentId = :agentId
        and version.deletedAt is null
      """)
  Optional<AiAgentVersion> findActiveByIdAndAgentIdForUpdate(
      @Param("id") String id,
      @Param("agentId") String agentId
  );

  @Override
  @Query("""
      select version from AiAgentVersion version
      where version.version = :versionName
        and version.agentId = :agentId
        and version.deletedAt is null
      """)
  Optional<AiAgentVersion> findActiveByVersionAndAgentId(
      @Param("versionName") String version,
      @Param("agentId") String agentId
  );

  @Override
  @Query("""
      select version from AiAgentVersion version
      where version.agentId = :agentId and version.deletedAt is null
      """)
  Page<AiAgentVersion> findAllActiveByAgentId(
      @Param("agentId") String agentId,
      Pageable pageable
  );

  @Override
  @Query("""
      select count(version) from AiAgentVersion version
      where version.agentId = :agentId and version.deletedAt is null
      """)
  long countActiveByAgentId(@Param("agentId") String agentId);

  @Override
  @Query(
      value = """
          select
            agent.id as resourceId,
            agent.code as resourceCode,
            coalesce(nullif(agent.name, ''), agent.code) as resourceName,
            version.id as resourceVersionId,
            version.version as resourceVersion,
            agent.scopeType as scopeType,
            version.publishedAt as publishedAt
          from AiAgentVersion version, AiAgentDefinition agent
          where version.agentId = agent.id
            and agent.deletedAt is null
            and version.deletedAt is null
            and agent.enabled = true
            and version.status = org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus.PUBLISHED
            and (
              (
                :tenantOwner = false
                and (agent.scopeType is null or agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and agent.tenantId is null
                and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and version.tenantId is null
              )
              or (
                :tenantOwner = true
                and (
                  (
                    (agent.scopeType is null or agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and agent.tenantId is null
                    and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and version.tenantId is null
                  )
                  or (
                    agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and agent.tenantId = :ownerTenantId
                    and version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and version.tenantId = :ownerTenantId
                  )
                )
              )
            )
            and (
              lower(agent.code) like :searchPattern escape '!'
              or lower(agent.name) like :searchPattern escape '!'
              or lower(version.version) like :searchPattern escape '!'
            )
          order by
            lower(coalesce(nullif(agent.name, ''), agent.code)),
            lower(agent.code),
            case when version.publishedAt is null then 1 else 0 end,
            version.publishedAt desc,
            lower(version.version) desc,
            agent.id,
            version.id
          """,
      countQuery = """
          select count(version)
          from AiAgentVersion version, AiAgentDefinition agent
          where version.agentId = agent.id
            and agent.deletedAt is null
            and version.deletedAt is null
            and agent.enabled = true
            and version.status = org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus.PUBLISHED
            and (
              (
                :tenantOwner = false
                and (agent.scopeType is null or agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and agent.tenantId is null
                and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and version.tenantId is null
              )
              or (
                :tenantOwner = true
                and (
                  (
                    (agent.scopeType is null or agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and agent.tenantId is null
                    and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and version.tenantId is null
                  )
                  or (
                    agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and agent.tenantId = :ownerTenantId
                    and version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and version.tenantId = :ownerTenantId
                  )
                )
              )
            )
            and (
              lower(agent.code) like :searchPattern escape '!'
              or lower(agent.name) like :searchPattern escape '!'
              or lower(version.version) like :searchPattern escape '!'
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
        agent.id as resourceId,
        agent.code as resourceCode,
        coalesce(nullif(agent.name, ''), agent.code) as resourceName,
        version.id as resourceVersionId,
        version.version as resourceVersion,
        agent.scopeType as scopeType,
        version.publishedAt as publishedAt,
        case
          when agent.enabled = true
            and version.status = org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus.PUBLISHED
            then true
          else false
        end as selectable,
        case
          when agent.enabled is null or agent.enabled = false
            then 'DEPENDENCY_DISABLED'
          when version.status = org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus.DEPRECATED
            then 'VERSION_DEPRECATED'
          else null
        end as availabilityCode
      from AiAgentVersion version, AiAgentDefinition agent
      where version.agentId = agent.id
        and agent.deletedAt is null
        and version.deletedAt is null
        and version.id in :resourceVersionIds
        and version.status in (
          org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus.PUBLISHED,
          org.simplepoint.plugin.ai.agent.api.model.AgentVersionStatus.DEPRECATED
        )
        and (
          (
            :tenantOwner = false
            and (agent.scopeType is null or agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
            and agent.tenantId is null
            and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
            and version.tenantId is null
          )
          or (
            :tenantOwner = true
            and (
              (
                (agent.scopeType is null or agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and agent.tenantId is null
                and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and version.tenantId is null
              )
              or (
                agent.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                and agent.tenantId = :ownerTenantId
                and version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                and version.tenantId = :ownerTenantId
              )
            )
          )
        )
      order by
        lower(coalesce(nullif(agent.name, ''), agent.code)),
        lower(agent.code),
        case when version.publishedAt is null then 1 else 0 end,
        version.publishedAt desc,
        lower(version.version) desc,
        agent.id,
        version.id
      """)
  List<AiDependencyResolutionView> resolveDependencyOptions(
      @Param("tenantOwner") boolean tenantOwner,
      @Param("ownerTenantId") String ownerTenantId,
      @Param("resourceVersionIds")
      Collection<String> resourceVersionIds
  );
}
