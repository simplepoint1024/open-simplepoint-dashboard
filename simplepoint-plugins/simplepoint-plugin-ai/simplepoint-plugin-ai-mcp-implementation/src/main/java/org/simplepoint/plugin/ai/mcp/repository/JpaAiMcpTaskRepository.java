package org.simplepoint.plugin.ai.mcp.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpTask;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpTaskRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA implementation of the durable MCP Task repository.
 */
@Repository
public interface JpaAiMcpTaskRepository
    extends BaseRepository<AiMcpTask, String>, AiMcpTaskRepository {

  @Override
  @Query("""
      select task from AiMcpTask task
      where task.id = :id and task.deletedAt is null
      """)
  Optional<AiMcpTask> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select task from AiMcpTask task
      where task.id = :id and task.deletedAt is null
      """)
  Optional<AiMcpTask> findActiveByIdForUpdate(@Param("id") String id);

  @Override
  @Query("""
      select task from AiMcpTask task
      where task.publicationCode = :publicationCode
        and task.subjectHash = :subjectHash
        and task.clientHash = :clientHash
        and task.expiresAt > :now
        and task.deletedAt is null
      order by task.createdAt desc, task.id desc
      """)
  List<AiMcpTask> findVisibleFirstPage(
      @Param("publicationCode") String publicationCode,
      @Param("subjectHash") String subjectHash,
      @Param("clientHash") String clientHash,
      @Param("now") Instant now,
      Pageable pageable
  );

  @Override
  @Query("""
      select task from AiMcpTask task
      where task.publicationCode = :publicationCode
        and task.subjectHash = :subjectHash
        and task.clientHash = :clientHash
        and task.expiresAt > :now
        and (
          task.createdAt < :beforeCreatedAt
          or (
            task.createdAt = :beforeCreatedAt
            and task.id < :beforeId
          )
        )
        and task.deletedAt is null
      order by task.createdAt desc, task.id desc
      """)
  List<AiMcpTask> findVisible(
      @Param("publicationCode") String publicationCode,
      @Param("subjectHash") String subjectHash,
      @Param("clientHash") String clientHash,
      @Param("now") Instant now,
      @Param("beforeCreatedAt") Instant beforeCreatedAt,
      @Param("beforeId") String beforeId,
      Pageable pageable
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(
      @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
  )
  @Query("""
      select task from AiMcpTask task
      where (
          (
            task.status =
              org.simplepoint.plugin.ai.mcp.api.model.McpTaskStatus.PENDING
            and task.nextAttemptAt <= :now
          )
          or (
            task.status =
              org.simplepoint.plugin.ai.mcp.api.model.McpTaskStatus.RUNNING
            and task.leaseExpiresAt <= :now
          )
        )
        and task.expiresAt > :now
        and task.deletedAt is null
      order by task.createdAt asc
      """)
  List<AiMcpTask> findClaimableForUpdate(
      @Param("now") Instant now,
      Pageable pageable
  );
}
