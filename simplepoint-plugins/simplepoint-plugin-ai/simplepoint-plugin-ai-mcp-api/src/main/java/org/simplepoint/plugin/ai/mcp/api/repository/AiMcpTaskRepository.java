package org.simplepoint.plugin.ai.mcp.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpTask;
import org.springframework.data.domain.Pageable;

/**
 * Persistence boundary for durable MCP Tool tasks.
 */
public interface AiMcpTaskRepository
    extends BaseRepository<AiMcpTask, String> {

  /**
   * Finds one non-deleted Task.
   */
  Optional<AiMcpTask> findActiveById(String id);

  /**
   * Locks and finds one non-deleted Task.
   */
  Optional<AiMcpTask> findActiveByIdForUpdate(String id);

  /**
   * Lists the first page of Tasks visible to an exact publication
   * authorization context.
   */
  List<AiMcpTask> findVisibleFirstPage(
      String publicationCode,
      String subjectHash,
      String clientHash,
      Instant now,
      Pageable pageable
  );

  /**
   * Lists a cursor-bounded page of Tasks visible to an exact publication
   * authorization context.
   */
  List<AiMcpTask> findVisible(
      String publicationCode,
      String subjectHash,
      String clientHash,
      Instant now,
      Instant beforeCreatedAt,
      String beforeId,
      Pageable pageable
  );

  /**
   * Claims due or abandoned Tasks without blocking another worker.
   */
  List<AiMcpTask> findClaimableForUpdate(
      Instant now,
      Pageable pageable
  );
}
