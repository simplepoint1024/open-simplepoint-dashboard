package org.simplepoint.plugin.ai.workflow.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.workflow.api.entity.AiWorkflowDependencyBinding;
import org.simplepoint.plugin.ai.workflow.api.repository.AiWorkflowDependencyBindingRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA Workflow dependency repository.
 */
@Repository
public interface JpaAiWorkflowDependencyBindingRepository
    extends BaseRepository<AiWorkflowDependencyBinding, String>,
    AiWorkflowDependencyBindingRepository {

  @Override
  @Query("""
      select binding from AiWorkflowDependencyBinding binding
      where binding.workflowVersionId = :workflowVersionId
        and binding.deletedAt is null
      order by binding.bindingOrder asc
      """)
  List<AiWorkflowDependencyBinding> findAllActiveByWorkflowVersionId(
      @Param("workflowVersionId") String workflowVersionId
  );
}
