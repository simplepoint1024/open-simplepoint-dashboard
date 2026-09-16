package org.simplepoint.plugin.ai.workflow.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowHumanTask;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowHumanTaskRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for durable Workflow human tasks.
 */
@Repository
public interface JpaAiWorkflowHumanTaskRepository
    extends BaseRepository<AiWorkflowHumanTask, String>,
    AiWorkflowHumanTaskRepository {

  @Override
  @Query("""
      select task from AiWorkflowHumanTask task
      where task.executionId = :executionId
        and task.deletedAt is null
      order by task.createdAt asc
      """)
  List<AiWorkflowHumanTask> findAllActiveByExecutionId(
      @Param("executionId") String executionId
  );

  @Override
  @Query("""
      select task from AiWorkflowHumanTask task
      where task.id = :id
        and task.executionId = :executionId
        and task.deletedAt is null
      """)
  Optional<AiWorkflowHumanTask> findActiveByIdAndExecutionId(
      @Param("id") String id,
      @Param("executionId") String executionId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select task from AiWorkflowHumanTask task
      where task.id = :id
        and task.executionId = :executionId
        and task.deletedAt is null
      """)
  Optional<AiWorkflowHumanTask> findActiveByIdAndExecutionIdForUpdate(
      @Param("id") String id,
      @Param("executionId") String executionId
  );

  @Override
  @Query("""
      select task from AiWorkflowHumanTask task
      where task.nodeExecutionId = :nodeExecutionId
        and task.deletedAt is null
      """)
  Optional<AiWorkflowHumanTask> findActiveByNodeExecutionId(
      @Param("nodeExecutionId") String nodeExecutionId
  );
}
