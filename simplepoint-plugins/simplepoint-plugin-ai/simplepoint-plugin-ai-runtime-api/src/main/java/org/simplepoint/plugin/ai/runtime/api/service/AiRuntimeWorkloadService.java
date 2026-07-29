package org.simplepoint.plugin.ai.runtime.api.service;

import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadSubmitRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Tenant-aware OCI workload submission and management contract.
 */
public interface AiRuntimeWorkloadService {

  /**
   * Persists an idempotent workload in the current platform or tenant scope.
   */
  AiRuntimeWorkload submit(RuntimeWorkloadSubmitRequest request);

  /**
   * Pages workloads owned by the current management scope.
   */
  Page<AiRuntimeWorkload> findAll(Pageable pageable);

  /**
   * Returns a workload when it belongs to the current management scope.
   */
  Optional<AiRuntimeWorkload> find(String workloadId);

  /**
   * Lists workloads owned by one pool in the current management scope.
   */
  List<AiRuntimeWorkload> findByPool(String poolId);

  /**
   * Requests cancellation of one workload in the current management scope.
   */
  AiRuntimeWorkload stop(String workloadId);
}
