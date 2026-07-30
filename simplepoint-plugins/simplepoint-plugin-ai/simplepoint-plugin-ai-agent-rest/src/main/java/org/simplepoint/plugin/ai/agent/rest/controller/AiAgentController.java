package org.simplepoint.plugin.ai.agent.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.function.Supplier;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.agent.api.constants.AiAgentPaths;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionDecisionRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionPauseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStartRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentHumanInterventionResponseRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.model.AgentUpsertRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionCreateRequest;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentMemoryService;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI workbench endpoints for Agent definitions and immutable versions.
 */
@RestController
@RequestMapping(AiAgentPaths.AGENTS)
@Tag(name = "AI Agent", description = "声明式 Agent 与不可变版本管理")
public class AiAgentController {

  private final AiAgentService service;

  private final AiAgentExecutionService executionService;

  private final AiAgentMemoryService memoryService;

  /**
   * Creates the Agent controller.
   */
  public AiAgentController(
      final AiAgentService service,
      final AiAgentExecutionService executionService,
      final AiAgentMemoryService memoryService
  ) {
    this.service = service;
    this.executionService = executionService;
    this.memoryService = memoryService;
  }

  /**
   * Pages Agents in the active platform or tenant context.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "分页查询 Agent")
  public Response<?> findAll(final Pageable pageable) {
    return invoke(() -> service.findAll(pageable));
  }

  /**
   * Returns one visible Agent.
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "查询 Agent")
  public Response<?> find(@PathVariable("id") final String id) {
    return invoke(() -> service.find(id).orElseThrow(
        () -> new IllegalArgumentException("Agent does not exist")
    ));
  }

  /**
   * Creates an Agent in the active context.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.create')"
  )
  @Operation(summary = "新增 Agent")
  public Response<?> create(@RequestBody final AgentUpsertRequest request) {
    return invoke(() -> service.create(request));
  }

  /**
   * Updates mutable Agent metadata.
   */
  @PutMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.edit')"
  )
  @Operation(summary = "修改 Agent 元数据")
  public Response<?> update(
      @PathVariable("id") final String id,
      @RequestBody final AgentUpsertRequest request
  ) {
    return invoke(() -> service.update(id, request));
  }

  /**
   * Deletes an Agent without versions.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.delete')"
  )
  @Operation(summary = "删除没有版本的 Agent")
  public Response<?> remove(@PathVariable("id") final String id) {
    return invoke(() -> {
      service.remove(id);
      return id;
    });
  }

  /**
   * Pages immutable versions for one Agent.
   */
  @GetMapping("/{id}/versions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "分页查询 Agent 不可变版本")
  public Response<?> versions(
      @PathVariable("id") final String id,
      final Pageable pageable
  ) {
    return invoke(() -> service.findVersions(id, pageable));
  }

  /**
   * Returns one immutable Agent version.
   */
  @GetMapping("/{id}/versions/{versionId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "查询 Agent 不可变版本")
  public Response<?> version(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.findVersion(id, versionId).orElseThrow(
        () -> new IllegalArgumentException("Agent version does not exist")
    ));
  }

  /**
   * Creates one immutable declarative Agent version.
   */
  @PostMapping("/{id}/versions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.agents.versions.manage')"
  )
  @Operation(summary = "创建 Agent 不可变版本")
  public Response<?> createVersion(
      @PathVariable("id") final String id,
      @RequestBody final AgentVersionCreateRequest request
  ) {
    return invoke(() -> service.createVersion(id, request));
  }

  /**
   * Publishes and activates an Agent version.
   */
  @PostMapping("/{id}/versions/{versionId}/publish")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.publish')"
  )
  @Operation(summary = "发布并激活 Agent 版本")
  public Response<?> publish(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.publishVersion(id, versionId));
  }

  /**
   * Deprecates a published Agent version.
   */
  @PostMapping("/{id}/versions/{versionId}/deprecate")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.publish')"
  )
  @Operation(summary = "废弃 Agent 版本")
  public Response<?> deprecate(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.deprecateVersion(id, versionId));
  }

  /**
   * Starts one durable execution of the active Agent version.
   */
  @PostMapping("/{id}/executions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.execute')"
  )
  @Operation(summary = "执行 Agent")
  public Response<?> execute(
      @PathVariable("id") final String id,
      @RequestBody final AgentExecutionStartRequest request
  ) {
    return invoke(() -> executionService.start(id, request));
  }

  /**
   * Pages durable Agent executions.
   */
  @GetMapping("/{id}/executions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "分页查询 Agent 执行记录")
  public Response<?> executions(
      @PathVariable("id") final String id,
      final Pageable pageable
  ) {
    return invoke(() -> executionService.findAll(id, pageable));
  }

  /**
   * Returns one Agent execution with model and Skill traces.
   */
  @GetMapping("/{id}/executions/{executionId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "查询 Agent 执行详情")
  public Response<?> execution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.find(id, executionId).orElseThrow(
        () -> new IllegalArgumentException("Agent execution does not exist")
    ));
  }

  /**
   * Pages model and Skill traces without loading every trace payload.
   */
  @GetMapping("/{id}/executions/{executionId}/traces")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "分页查询 Agent 执行 Trace")
  public Response<?> traces(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestParam(name = "type", required = false)
      final AgentTraceType type,
      @RequestParam(name = "status", required = false)
      final AgentTraceStatus status,
      final Pageable pageable
  ) {
    return invoke(() -> executionService.findTraces(
        id,
        executionId,
        type,
        status,
        pageable
    ));
  }

  /**
   * Reads durable execution events using an exclusive sequence cursor.
   */
  @GetMapping("/{id}/executions/{executionId}/events")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "增量查询 Agent 执行事件")
  public Response<?> events(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestParam(name = "after", defaultValue = "-1")
      final long after,
      @RequestParam(name = "limit", defaultValue = "100")
      final int limit
  ) {
    return invoke(() -> executionService.findEvents(
        id,
        executionId,
        after,
        limit
    ));
  }

  /**
   * Returns restart-safe Agent execution and Trace metrics.
   */
  @GetMapping("/{id}/metrics")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "查询 Agent 持久化运行指标")
  public Response<?> metrics(
      @PathVariable("id") final String id,
      @RequestParam(name = "from", required = false)
      final Instant from,
      @RequestParam(name = "to", required = false)
      final Instant to
  ) {
    return invoke(() -> executionService.metrics(id, from, to));
  }

  /**
   * Lists current-subject long-term memories for one Agent.
   */
  @GetMapping("/{id}/memories")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.view')"
  )
  @Operation(summary = "查询当前主体的 Agent 长期记忆")
  public Response<?> memories(@PathVariable("id") final String id) {
    return invoke(() -> memoryService.findCurrentSubjectMemories(id));
  }

  /**
   * Permanently removes one current-subject long-term memory.
   */
  @DeleteMapping("/{id}/memories/{memoryId}")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.agents.memory.manage')"
  )
  @Operation(summary = "删除当前主体的 Agent 长期记忆")
  public Response<?> removeMemory(
      @PathVariable("id") final String id,
      @PathVariable("memoryId") final String memoryId
  ) {
    return invoke(() -> {
      memoryService.removeCurrentSubjectMemory(id, memoryId);
      return memoryId;
    });
  }

  /**
   * Approves an Agent execution waiting on its immutable policy.
   */
  @PostMapping("/{id}/executions/{executionId}/approve")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.approve')"
  )
  @Operation(summary = "审批通过 Agent 执行")
  public Response<?> approveExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false)
      final AgentExecutionDecisionRequest request
  ) {
    return invoke(() -> executionService.approve(id, executionId, request));
  }

  /**
   * Rejects an Agent execution waiting on its immutable policy.
   */
  @PostMapping("/{id}/executions/{executionId}/reject")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.approve')"
  )
  @Operation(summary = "驳回 Agent 执行")
  public Response<?> rejectExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false)
      final AgentExecutionDecisionRequest request
  ) {
    return invoke(() -> executionService.reject(id, executionId, request));
  }

  /**
   * Requests a cooperative pause at the next safe checkpoint.
   */
  @PostMapping("/{id}/executions/{executionId}/pause")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.control')"
  )
  @Operation(summary = "暂停 Agent 执行")
  public Response<?> pauseExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false)
      final AgentExecutionPauseRequest request
  ) {
    return invoke(() -> executionService.pause(id, executionId, request));
  }

  /**
   * Resumes one cooperatively paused Agent execution.
   */
  @PostMapping("/{id}/executions/{executionId}/resume")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.control')"
  )
  @Operation(summary = "恢复 Agent 执行")
  public Response<?> resumeExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.resume(id, executionId));
  }

  /**
   * Requests operator input at the next safe execution checkpoint.
   */
  @PostMapping("/{id}/executions/{executionId}/interventions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.agents.intervene')"
  )
  @Operation(summary = "请求 Agent 人工介入")
  public Response<?> requestHumanIntervention(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody final AgentHumanInterventionRequest request
  ) {
    return invoke(() -> executionService.requestHumanIntervention(
        id,
        executionId,
        request
    ));
  }

  /**
   * Continues or cancels one waiting human-intervention task.
   */
  @PostMapping(
      "/{id}/executions/{executionId}/interventions/{interventionId}/respond"
  )
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.agents.intervene')"
  )
  @Operation(summary = "提交 Agent 人工介入结果")
  public Response<?> respondHumanIntervention(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @PathVariable("interventionId") final String interventionId,
      @RequestBody final AgentHumanInterventionResponseRequest request
  ) {
    return invoke(() -> executionService.respondHumanIntervention(
        id,
        executionId,
        interventionId,
        request
    ));
  }

  /**
   * Cancels a non-terminal Agent execution.
   */
  @PostMapping("/{id}/executions/{executionId}/cancel")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.agents.execute')"
  )
  @Operation(summary = "取消 Agent 执行")
  public Response<?> cancelExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.cancel(id, executionId));
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return Response.okay(operation.get());
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Response.of(
          ResponseEntity.badRequest()
              .contentType(MediaType.TEXT_PLAIN)
              .body(ex.getMessage())
      );
    }
  }
}
