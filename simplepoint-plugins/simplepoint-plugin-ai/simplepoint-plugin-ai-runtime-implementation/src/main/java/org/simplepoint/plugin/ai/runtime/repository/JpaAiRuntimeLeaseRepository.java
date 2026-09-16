package org.simplepoint.plugin.ai.runtime.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeLease;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeLeaseStatus;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeLeaseRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for fenced scheduler leases.
 */
@Repository
public interface JpaAiRuntimeLeaseRepository
    extends BaseRepository<AiRuntimeLease, String>, AiRuntimeLeaseRepository {

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select lease from AiRuntimeLease lease
      where lease.workloadId = :workloadId
        and lease.status = org.simplepoint.plugin.ai.runtime.api.model.RuntimeLeaseStatus.ACTIVE
        and lease.deletedAt is null
      """)
  Optional<AiRuntimeLease> findActiveByWorkloadForUpdate(
      @Param("workloadId") String workloadId
  );

  @Override
  @Query("""
      select lease from AiRuntimeLease lease
      where lease.nodeId = :nodeId
        and lease.status = :status
        and lease.deletedAt is null
      """)
  List<AiRuntimeLease> findByNodeAndStatus(
      @Param("nodeId") String nodeId,
      @Param("status") RuntimeLeaseStatus status
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select lease from AiRuntimeLease lease
      where lease.status = :status
        and lease.expiresAt < :now
        and lease.deletedAt is null
      order by lease.expiresAt asc
      """)
  List<AiRuntimeLease> findExpiredActiveLeases(
      @Param("status") RuntimeLeaseStatus status,
      @Param("now") Instant now
  );
}
