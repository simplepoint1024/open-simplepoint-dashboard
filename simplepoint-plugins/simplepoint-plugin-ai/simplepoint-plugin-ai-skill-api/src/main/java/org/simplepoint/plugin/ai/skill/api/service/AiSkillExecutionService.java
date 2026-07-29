package org.simplepoint.plugin.ai.skill.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Scope-aware API for durable Skill workflow execution.
 */
public interface AiSkillExecutionService {

  /**
   * Starts an idempotent execution of the active published version.
   */
  AiSkillExecution start(
      String skillId,
      SkillExecutionStartRequest request
  );

  /**
   * Pages durable executions owned by one visible Skill.
   */
  Page<AiSkillExecution> findAll(String skillId, Pageable pageable);

  /**
   * Finds one visible durable execution with step checkpoints.
   */
  Optional<AiSkillExecution> find(String skillId, String executionId);
}
