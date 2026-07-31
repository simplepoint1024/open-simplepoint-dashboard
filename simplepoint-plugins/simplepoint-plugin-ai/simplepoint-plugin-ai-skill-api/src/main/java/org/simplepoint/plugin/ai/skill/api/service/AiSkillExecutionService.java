package org.simplepoint.plugin.ai.skill.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillAgentExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillWorkflowExecutionCommand;
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
   * Starts the exact published Skill version pinned by an Agent version.
   *
   * <p>This internal Java contract is deliberately not exposed as a public
   * REST endpoint.</p>
   */
  AiSkillExecution startVersionForAgent(SkillAgentExecutionCommand command);

  /**
   * Starts the exact published Skill version pinned by a Workflow.
   *
   * <p>This internal Java contract is deliberately not exposed as a public
   * REST endpoint.</p>
   */
  AiSkillExecution startVersionForWorkflow(
      SkillWorkflowExecutionCommand command
  );

  /**
   * Pages durable executions owned by one visible Skill.
   */
  Page<AiSkillExecution> findAll(String skillId, Pageable pageable);

  /**
   * Finds one visible durable execution with step checkpoints.
   */
  Optional<AiSkillExecution> find(String skillId, String executionId);

  /**
   * Approves an execution waiting on its immutable version policy.
   */
  AiSkillExecution approve(
      String skillId,
      String executionId,
      SkillExecutionDecisionRequest request
  );

  /**
   * Rejects an execution waiting on its immutable version policy.
   */
  AiSkillExecution reject(
      String skillId,
      String executionId,
      SkillExecutionDecisionRequest request
  );

  /**
   * Requests a cooperative pause at the next safe workflow checkpoint.
   */
  AiSkillExecution pause(
      String skillId,
      String executionId,
      SkillExecutionPauseRequest request
  );

  /**
   * Resumes a paused execution or returns it to approval waiting.
   */
  AiSkillExecution resume(String skillId, String executionId);
}
