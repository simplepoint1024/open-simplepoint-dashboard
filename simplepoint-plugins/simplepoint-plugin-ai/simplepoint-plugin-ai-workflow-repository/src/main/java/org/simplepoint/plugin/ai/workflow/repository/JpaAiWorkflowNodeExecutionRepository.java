package org.simplepoint.plugin.ai.workflow.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowNodeExecution;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowNodeExecutionRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for durable Workflow node checkpoints.
 */
@Repository
public interface JpaAiWorkflowNodeExecutionRepository
    extends BaseRepository<AiWorkflowNodeExecution, String>,
    AiWorkflowNodeExecutionRepository {

  @Override
  @Query("""
      select node from AiWorkflowNodeExecution node
      where node.executionId = :executionId
        and node.deletedAt is null
      order by node.nodeOrder asc
      """)
  List<AiWorkflowNodeExecution> findAllActiveByExecutionId(
      @Param("executionId") String executionId
  );

  @Override
  @Query("""
      select node from AiWorkflowNodeExecution node
      where node.executionId = :executionId
        and node.nodeId = :nodeId
        and node.deletedAt is null
      """)
  Optional<AiWorkflowNodeExecution> findActiveByExecutionIdAndNodeId(
      @Param("executionId") String executionId,
      @Param("nodeId") String nodeId
  );

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select node from AiWorkflowNodeExecution node
      where node.executionId = :executionId
        and node.nodeId = :nodeId
        and node.deletedAt is null
      """)
  Optional<AiWorkflowNodeExecution>
      findActiveByExecutionIdAndNodeIdForUpdate(
          @Param("executionId") String executionId,
          @Param("nodeId") String nodeId
      );
}
