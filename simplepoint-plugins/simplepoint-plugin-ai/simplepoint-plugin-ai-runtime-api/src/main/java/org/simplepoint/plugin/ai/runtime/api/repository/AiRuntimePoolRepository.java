package org.simplepoint.plugin.ai.runtime.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for reusable OCI MCP runtime pools.
 */
public interface AiRuntimePoolRepository
    extends BaseRepository<AiRuntimePool, String> {

  /**
   * Locks one non-deleted pool by its current-scope code.
   */
  Optional<AiRuntimePool> findActiveByCodeForUpdate(
      AiResourceScope scopeType,
      String tenantId,
      String code
  );

  /**
   * Locks one non-deleted pool by internal identifier.
   */
  Optional<AiRuntimePool> findActiveByIdForUpdate(String poolId);

  /**
   * Finds the managed pool bound to one MCP server in the exact owner scope.
   */
  Optional<AiRuntimePool> findActiveByServerAndScope(
      String serverId,
      AiResourceScope scopeType,
      String tenantId
  );

  /**
   * Pages non-deleted pools in one management scope.
   */
  Page<AiRuntimePool> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );

  /**
   * Claims a bounded pool reconciliation batch without blocking peers.
   */
  List<AiRuntimePool> findReconcileForUpdate(
      List<RuntimePoolStatus> statuses,
      Pageable pageable
  );
}
