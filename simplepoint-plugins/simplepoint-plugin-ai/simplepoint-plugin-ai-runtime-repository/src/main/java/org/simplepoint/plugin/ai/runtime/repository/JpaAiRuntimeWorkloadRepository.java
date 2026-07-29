package org.simplepoint.plugin.ai.runtime.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeWorkloadRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for durable OCI MCP workloads.
 */
@Repository
public interface JpaAiRuntimeWorkloadRepository
    extends BaseRepository<AiRuntimeWorkload, String>,
    AiRuntimeWorkloadRepository {

  @Override
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.executionId = :executionId
        and workload.deletedAt is null
      """)
  Optional<AiRuntimeWorkload> findActiveByExecutionId(
      @Param("executionId") String executionId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.executionId = :executionId
        and workload.deletedAt is null
      """)
  Optional<AiRuntimeWorkload> findActiveByExecutionIdForUpdate(
      @Param("executionId") String executionId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.id = :workloadId
        and workload.deletedAt is null
      """)
  Optional<AiRuntimeWorkload> findActiveByIdForUpdate(
      @Param("workloadId") String workloadId
  );

  @Override
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.scopeType = :scopeType
        and ((:tenantId is null and workload.tenantId is null)
          or workload.tenantId = :tenantId)
        and workload.deletedAt is null
      order by workload.createdAt desc
      """)
  Page<AiRuntimeWorkload> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );

  @Override
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.assignedNodeId = :nodeId
        and workload.status in :statuses
        and workload.deletedAt is null
      order by workload.createdAt asc
      """)
  List<AiRuntimeWorkload> findActiveByNodeAndStatuses(
      @Param("nodeId") String nodeId,
      @Param("statuses") List<RuntimeWorkloadStatus> statuses
  );

  @Override
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.poolId = :poolId
        and workload.deletedAt is null
      order by workload.replicaSequence asc
      """)
  List<AiRuntimeWorkload> findActiveByPool(
      @Param("poolId") String poolId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.status =
          org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus.PENDING
        and workload.deletedAt is null
      order by workload.createdAt asc
      """)
  List<AiRuntimeWorkload> findPendingForUpdate(
      Pageable pageable
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.status in :statuses
        and (workload.lastObservedAt is null
          or workload.lastObservedAt <= :retryBefore)
        and workload.deletedAt is null
      order by workload.createdAt asc
      """)
  List<AiRuntimeWorkload> findDispatchableForUpdate(
      @Param("statuses") List<RuntimeWorkloadStatus> statuses,
      @Param("retryBefore") Instant retryBefore,
      Pageable pageable
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select workload from AiRuntimeWorkload workload
      where workload.status in :statuses
        and (workload.deadlineAt <= :now
          or workload.lastObservedAt is null
          or workload.lastObservedAt <= :observeBefore)
        and workload.deletedAt is null
      order by workload.deadlineAt asc, workload.createdAt asc
      """)
  List<AiRuntimeWorkload> findObservableForUpdate(
      @Param("statuses") List<RuntimeWorkloadStatus> statuses,
      @Param("now") Instant now,
      @Param("observeBefore") Instant observeBefore,
      Pageable pageable
  );
}
