package org.simplepoint.plugin.ai.workflow.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowVersion;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA Workflow version repository.
 */
@Repository
public interface JpaAiWorkflowVersionRepository
    extends BaseRepository<AiWorkflowVersion, String>,
    AiWorkflowVersionRepository {

  @Override
  @Query("""
      select version from AiWorkflowVersion version
      where version.id = :id and version.deletedAt is null
      """)
  Optional<AiWorkflowVersion> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select version from AiWorkflowVersion version
      where version.id = :id
        and version.workflowId = :workflowId
        and version.deletedAt is null
      """)
  Optional<AiWorkflowVersion> findActiveByIdAndWorkflowId(
      @Param("id") String id,
      @Param("workflowId") String workflowId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select version from AiWorkflowVersion version
      where version.id = :id
        and version.workflowId = :workflowId
        and version.deletedAt is null
      """)
  Optional<AiWorkflowVersion> findActiveByIdAndWorkflowIdForUpdate(
      @Param("id") String id,
      @Param("workflowId") String workflowId
  );

  @Override
  @Query("""
      select version from AiWorkflowVersion version
      where version.version = :versionName
        and version.workflowId = :workflowId
        and version.deletedAt is null
      """)
  Optional<AiWorkflowVersion> findActiveByVersionAndWorkflowId(
      @Param("versionName") String version,
      @Param("workflowId") String workflowId
  );

  @Override
  @Query("""
      select version from AiWorkflowVersion version
      where version.workflowId = :workflowId
        and version.deletedAt is null
      order by version.createdAt desc
      """)
  Page<AiWorkflowVersion> findAllActiveByWorkflowId(
      @Param("workflowId") String workflowId,
      Pageable pageable
  );

  @Override
  @Query("""
      select count(version) from AiWorkflowVersion version
      where version.workflowId = :workflowId
        and version.deletedAt is null
      """)
  long countActiveByWorkflowId(@Param("workflowId") String workflowId);
}
