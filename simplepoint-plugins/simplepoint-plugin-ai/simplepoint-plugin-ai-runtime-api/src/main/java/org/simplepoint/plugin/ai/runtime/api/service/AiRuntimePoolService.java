package org.simplepoint.plugin.ai.runtime.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolScaleRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolUpsertRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Current-scope management for reusable OCI MCP runtime pools.
 */
public interface AiRuntimePoolService {

  /**
   * Creates or replaces desired state by current-scope code.
   */
  AiRuntimePool upsert(RuntimePoolUpsertRequest request);

  /**
   * Pages current-scope runtime pools.
   */
  Page<AiRuntimePool> findAll(Pageable pageable);

  /**
   * Finds a readable current-scope runtime pool.
   */
  Optional<AiRuntimePool> find(String poolId);

  /**
   * Finds the current-scope pool owned by one managed MCP server.
   */
  Optional<AiRuntimePool> findByServer(String serverId);

  /**
   * Changes explicit desired replicas and refreshes activity.
   */
  AiRuntimePool scale(String poolId, RuntimePoolScaleRequest request);

  /**
   * Restores activation capacity and refreshes the idle timer.
   */
  AiRuntimePool activate(String poolId);

  /**
   * Disables a pool and requests full replica cleanup.
   */
  AiRuntimePool disable(String poolId);

  /**
   * Replaces all active replicas while preserving the pool desired state.
   */
  AiRuntimePool redeploy(String poolId);

  /**
   * Soft-deletes a disabled pool after all replicas have been reclaimed.
   */
  void remove(String poolId);
}
