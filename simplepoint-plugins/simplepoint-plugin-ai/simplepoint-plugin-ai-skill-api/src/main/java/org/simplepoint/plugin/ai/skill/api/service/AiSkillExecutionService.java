package org.simplepoint.plugin.ai.skill.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillExecution;
import org.simplepoint.plugin.ai.skill.api.model.SkillAgentExecutionCommand;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftDebugExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRun;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBreakpointsRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionCancelRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionEventFeed;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillPinnedChildCancelCommand;
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
   * Starts a LIVE debug execution pinned to one immutable Draft Revision.
   */
  AiSkillExecution startDraftDebug(
      String skillId,
      SkillDraftDebugExecutionStartRequest request
  );

  /** Starts all enabled Mock cases from one immutable Draft Revision. */
  SkillDraftMockTestRun startDraftMockTestRun(
      String skillId,
      SkillDraftMockTestRunStartRequest request
  );

  /** Pages durable Mock regression runs for one visible Skill. */
  Page<SkillDraftMockTestRun> findAllDraftMockTestRuns(
      String skillId,
      Pageable pageable
  );

  /** Finds one durable Mock regression run and its ordered case results. */
  Optional<SkillDraftMockTestRun> findDraftMockTestRun(
      String skillId,
      String testRunId
  );

  /** Pages debug executions owned by one visible Skill. */
  Page<AiSkillExecution> findAllDraftDebug(
      String skillId,
      Pageable pageable
  );

  /** Finds one visible debug execution with durable step checkpoints. */
  Optional<AiSkillExecution> findDraftDebug(
      String skillId,
      String executionId
  );

  /** Reads a bounded Draft debug event page after an exclusive cursor. */
  SkillExecutionEventFeed findDraftDebugEvents(
      String skillId,
      String executionId,
      long afterSequence,
      int limit
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
   * Cancels the exact published child execution pinned by a parent runtime.
   *
   * <p>This transaction-bound Java contract is deliberately not exposed as a
   * public REST endpoint and does not use the current management scope.</p>
   */
  AiSkillExecution cancelPinnedChild(
      SkillPinnedChildCancelCommand command
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

  /** Cooperatively cancels at the next safe execution checkpoint. */
  AiSkillExecution cancel(
      String skillId,
      String executionId,
      SkillExecutionCancelRequest request
  );

  /** Replaces safe pre-step breakpoints for a Draft debug execution. */
  AiSkillExecution setDraftDebugBreakpoints(
      String skillId,
      String executionId,
      SkillExecutionBreakpointsRequest request
  );
}
