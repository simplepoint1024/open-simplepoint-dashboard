package org.simplepoint.plugin.ai.skill.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyOptionView;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyResolutionView;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for immutable Skill versions.
 */
@Repository
public interface JpaAiSkillVersionRepository
    extends BaseRepository<AiSkillVersion, String>,
    AiSkillVersionRepository {

  @Override
  @Query("""
      select version from AiSkillVersion version
      where version.id = :id and version.deletedAt is null
      """)
  Optional<AiSkillVersion> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select version from AiSkillVersion version
      where version.id in :ids and version.deletedAt is null
      order by version.id
      """)
  List<AiSkillVersion> findAllActiveByIdIn(@Param("ids") Collection<String> ids);

  @Override
  @Query("""
      select version from AiSkillVersion version
      where version.id = :id
        and version.skillId = :skillId
        and version.deletedAt is null
      """)
  Optional<AiSkillVersion> findActiveByIdAndSkillId(
      @Param("id") String id,
      @Param("skillId") String skillId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select version from AiSkillVersion version
      where version.id = :id
        and version.skillId = :skillId
        and version.deletedAt is null
      """)
  Optional<AiSkillVersion> findActiveByIdAndSkillIdForUpdate(
      @Param("id") String id,
      @Param("skillId") String skillId
  );

  @Override
  @Query("""
      select version from AiSkillVersion version
      where version.version = :versionName
        and version.skillId = :skillId
        and version.deletedAt is null
      """)
  Optional<AiSkillVersion> findActiveByVersionAndSkillId(
      @Param("versionName") String version,
      @Param("skillId") String skillId
  );

  @Override
  @Query("""
      select version from AiSkillVersion version
      where version.skillId = :skillId and version.deletedAt is null
      """)
  Page<AiSkillVersion> findAllActiveBySkillId(
      @Param("skillId") String skillId,
      Pageable pageable
  );

  @Override
  @Query("""
      select count(version) from AiSkillVersion version
      where version.skillId = :skillId and version.deletedAt is null
      """)
  long countActiveBySkillId(@Param("skillId") String skillId);

  @Override
  @Query(
      value = """
          select
            skill.id as resourceId,
            skill.code as resourceCode,
            coalesce(nullif(skill.name, ''), skill.code) as resourceName,
            version.id as resourceVersionId,
            version.version as resourceVersion,
            skill.scopeType as scopeType,
            version.publishedAt as publishedAt
          from AiSkillVersion version, AiSkillDefinition skill
          where version.skillId = skill.id
            and skill.deletedAt is null
            and version.deletedAt is null
            and skill.enabled = true
            and version.status = org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus.PUBLISHED
            and (
              (
                :tenantOwner = false
                and (skill.scopeType is null or skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and skill.tenantId is null
                and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and version.tenantId is null
              )
              or (
                :tenantOwner = true
                and (
                  (
                    (skill.scopeType is null or skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and skill.tenantId is null
                    and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and version.tenantId is null
                  )
                  or (
                    skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and skill.tenantId = :ownerTenantId
                    and version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and version.tenantId = :ownerTenantId
                  )
                )
              )
            )
            and (
              lower(skill.code) like :searchPattern escape '!'
              or lower(skill.name) like :searchPattern escape '!'
              or lower(version.version) like :searchPattern escape '!'
            )
          order by
            lower(coalesce(nullif(skill.name, ''), skill.code)),
            lower(skill.code),
            case when version.publishedAt is null then 1 else 0 end,
            version.publishedAt desc,
            lower(version.version) desc,
            skill.id,
            version.id
          """,
      countQuery = """
          select count(version)
          from AiSkillVersion version, AiSkillDefinition skill
          where version.skillId = skill.id
            and skill.deletedAt is null
            and version.deletedAt is null
            and skill.enabled = true
            and version.status = org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus.PUBLISHED
            and (
              (
                :tenantOwner = false
                and (skill.scopeType is null or skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and skill.tenantId is null
                and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and version.tenantId is null
              )
              or (
                :tenantOwner = true
                and (
                  (
                    (skill.scopeType is null or skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and skill.tenantId is null
                    and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                    and version.tenantId is null
                  )
                  or (
                    skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and skill.tenantId = :ownerTenantId
                    and version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                    and version.tenantId = :ownerTenantId
                  )
                )
              )
            )
            and (
              lower(skill.code) like :searchPattern escape '!'
              or lower(skill.name) like :searchPattern escape '!'
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
        skill.id as resourceId,
        skill.code as resourceCode,
        coalesce(nullif(skill.name, ''), skill.code) as resourceName,
        version.id as resourceVersionId,
        version.version as resourceVersion,
        skill.scopeType as scopeType,
        version.publishedAt as publishedAt,
        case
          when skill.enabled = true
            and version.status = org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus.PUBLISHED
            then true
          else false
        end as selectable,
        case
          when skill.enabled is null or skill.enabled = false
            then 'DEPENDENCY_DISABLED'
          when version.status = org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus.DEPRECATED
            then 'VERSION_DEPRECATED'
          else null
        end as availabilityCode
      from AiSkillVersion version, AiSkillDefinition skill
      where version.skillId = skill.id
        and skill.deletedAt is null
        and version.deletedAt is null
        and version.id in :resourceVersionIds
        and version.status in (
          org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus.PUBLISHED,
          org.simplepoint.plugin.ai.skill.api.model.SkillVersionStatus.DEPRECATED
        )
        and (
          (
            :tenantOwner = false
            and (skill.scopeType is null or skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
            and skill.tenantId is null
            and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
            and version.tenantId is null
          )
          or (
            :tenantOwner = true
            and (
              (
                (skill.scopeType is null or skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and skill.tenantId is null
                and (version.scopeType is null or version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.SYSTEM)
                and version.tenantId is null
              )
              or (
                skill.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                and skill.tenantId = :ownerTenantId
                and version.scopeType = org.simplepoint.plugin.ai.core.api.model.AiResourceScope.TENANT
                and version.tenantId = :ownerTenantId
              )
            )
          )
        )
      order by
        lower(coalesce(nullif(skill.name, ''), skill.code)),
        lower(skill.code),
        case when version.publishedAt is null then 1 else 0 end,
        version.publishedAt desc,
        lower(version.version) desc,
        skill.id,
        version.id
      """)
  List<AiDependencyResolutionView> resolveDependencyOptions(
      @Param("tenantOwner") boolean tenantOwner,
      @Param("ownerTenantId") String ownerTenantId,
      @Param("resourceVersionIds")
      Collection<String> resourceVersionIds
  );
}
