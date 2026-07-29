package org.simplepoint.plugin.ai.skill.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
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
}
