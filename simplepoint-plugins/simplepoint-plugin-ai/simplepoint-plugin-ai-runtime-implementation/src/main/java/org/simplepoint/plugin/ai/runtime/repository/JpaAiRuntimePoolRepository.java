package org.simplepoint.plugin.ai.runtime.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolStatus;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for reusable OCI MCP runtime pools.
 */
@Repository
public interface JpaAiRuntimePoolRepository
    extends BaseRepository<AiRuntimePool, String>,
    AiRuntimePoolRepository {

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select pool from AiRuntimePool pool
      where pool.scopeType = :scopeType
        and ((:tenantId is null and pool.tenantId is null)
          or pool.tenantId = :tenantId)
        and pool.code = :code
        and pool.deletedAt is null
      """)
  Optional<AiRuntimePool> findActiveByCodeForUpdate(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("code") String code
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select pool from AiRuntimePool pool
      where pool.id = :poolId
        and pool.deletedAt is null
      """)
  Optional<AiRuntimePool> findActiveByIdForUpdate(
      @Param("poolId") String poolId
  );

  @Override
  @Query("""
      select pool from AiRuntimePool pool
      where pool.serverId = :serverId
        and pool.scopeType = :scopeType
        and ((:tenantId is null and pool.tenantId is null)
          or pool.tenantId = :tenantId)
        and pool.deletedAt is null
      """)
  Optional<AiRuntimePool> findActiveByServerAndScope(
      @Param("serverId") String serverId,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );

  @Override
  @Query("""
      select pool from AiRuntimePool pool
      where pool.scopeType = :scopeType
        and ((:tenantId is null and pool.tenantId is null)
          or pool.tenantId = :tenantId)
        and pool.deletedAt is null
      order by pool.createdAt desc
      """)
  Page<AiRuntimePool> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      Pageable pageable
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select pool from AiRuntimePool pool
      where pool.status in :statuses
        and pool.deletedAt is null
      order by pool.updatedAt asc
      """)
  List<AiRuntimePool> findReconcileForUpdate(
      @Param("statuses") List<RuntimePoolStatus> statuses,
      Pageable pageable
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select pool from AiRuntimePool pool
      where pool.runtimeProfileId = :profileId
        and pool.deletedAt is null
      order by pool.createdAt asc
      """)
  List<AiRuntimePool> findActiveByRuntimeProfileForUpdate(
      @Param("profileId") String profileId
  );
}
