package org.simplepoint.plugin.ai.runtime.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeNodeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for platform OCI runtime nodes.
 */
@Repository
public interface JpaAiRuntimeNodeRepository
    extends BaseRepository<AiRuntimeNode, String>, AiRuntimeNodeRepository {

  @Override
  @Query("""
      select node from AiRuntimeNode node
      where node.nodeId = :nodeId and node.deletedAt is null
      """)
  Optional<AiRuntimeNode> findActiveByNodeId(
      @Param("nodeId") String nodeId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select node from AiRuntimeNode node
      where node.nodeId = :nodeId and node.deletedAt is null
      """)
  Optional<AiRuntimeNode> findActiveByNodeIdForUpdate(
      @Param("nodeId") String nodeId
  );

  @Override
  @Query("""
      select node from AiRuntimeNode node
      where node.deletedAt is null
      order by node.status asc, node.nodeId asc
      """)
  Page<AiRuntimeNode> findAllActive(Pageable pageable);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select node from AiRuntimeNode node
      where node.deletedAt is null
        and node.status in :statuses
        and node.heartbeatExpiresAt < :now
      """)
  List<AiRuntimeNode> findExpiredNodes(
      @Param("now") Instant now,
      @Param("statuses") List<RuntimeNodeStatus> statuses
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select node from AiRuntimeNode node
      where node.deletedAt is null
        and node.status = org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus.READY
        and node.heartbeatExpiresAt >= :now
      order by node.runningWorkloads asc, node.nodeId asc
      """)
  List<AiRuntimeNode> findSchedulableNodesForUpdate(
      @Param("now") Instant now
  );
}
