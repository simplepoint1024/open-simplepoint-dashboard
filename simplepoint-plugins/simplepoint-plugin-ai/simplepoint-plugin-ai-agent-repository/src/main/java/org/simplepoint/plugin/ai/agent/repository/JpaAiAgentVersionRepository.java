package org.simplepoint.plugin.ai.agent.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentVersionRepository;
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
}
