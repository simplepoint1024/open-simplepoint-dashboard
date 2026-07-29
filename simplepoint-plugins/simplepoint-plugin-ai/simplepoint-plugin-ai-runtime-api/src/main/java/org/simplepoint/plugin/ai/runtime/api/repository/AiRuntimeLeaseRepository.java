package org.simplepoint.plugin.ai.runtime.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeLease;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeLeaseStatus;

/**
 * Repository contract for fenced workload leases.
 */
public interface AiRuntimeLeaseRepository
    extends BaseRepository<AiRuntimeLease, String> {

  /**
   * Locks the current active lease for one workload.
   */
  Optional<AiRuntimeLease> findActiveByWorkloadForUpdate(String workloadId);

  /**
   * Finds active leases owned by one runtime node.
   */
  List<AiRuntimeLease> findByNodeAndStatus(
      String nodeId,
      RuntimeLeaseStatus status
  );

  /**
   * Finds active leases past their expiry deadline.
   */
  List<AiRuntimeLease> findExpiredActiveLeases(
      RuntimeLeaseStatus status,
      Instant now
  );
}
