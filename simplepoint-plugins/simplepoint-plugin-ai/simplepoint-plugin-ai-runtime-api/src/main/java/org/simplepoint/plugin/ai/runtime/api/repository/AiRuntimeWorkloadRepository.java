package org.simplepoint.plugin.ai.runtime.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for durable OCI workloads.
 */
public interface AiRuntimeWorkloadRepository
    extends BaseRepository<AiRuntimeWorkload, String> {

  /**
   * Finds an active workload by its owning execution.
   */
  Optional<AiRuntimeWorkload> findActiveByExecutionId(String executionId);

  /**
   * Locks an existing workload for an idempotent submission decision.
   */
  Optional<AiRuntimeWorkload> findActiveByExecutionIdForUpdate(
      String executionId
  );

  /**
   * Locks one non-deleted workload by its internal identifier.
   */
  Optional<AiRuntimeWorkload> findActiveByIdForUpdate(String workloadId);

  /**
   * Pages workloads in one management scope.
   */
  Page<AiRuntimeWorkload> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );

  /**
   * Finds assigned workloads in selected lifecycle states.
   */
  List<AiRuntimeWorkload> findActiveByNodeAndStatuses(
      String nodeId,
      List<RuntimeWorkloadStatus> statuses
  );

  /**
   * Finds non-deleted replicas owned by one runtime pool.
   */
  List<AiRuntimeWorkload> findActiveByPool(String poolId);

  /**
   * Claims pending workloads without blocking another scheduler replica.
   */
  List<AiRuntimeWorkload> findPendingForUpdate(
      Pageable pageable
  );

  /**
   * Claims assignments requiring a first or retried runtime dispatch.
   */
  List<AiRuntimeWorkload> findDispatchableForUpdate(
      List<RuntimeWorkloadStatus> statuses,
      Instant retryBefore,
      Pageable pageable
  );

  /**
   * Claims running or stopping workloads requiring observation.
   */
  List<AiRuntimeWorkload> findObservableForUpdate(
      List<RuntimeWorkloadStatus> statuses,
      Instant now,
      Instant observeBefore,
      Pageable pageable
  );
}
