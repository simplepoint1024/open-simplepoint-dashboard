package org.simplepoint.plugin.ai.skill.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.function.Supplier;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.skill.api.constants.AiSkillPaths;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionDecisionRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillUpsertRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionCreateRequest;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * AI workbench endpoints for Skill definitions and immutable versions.
 */
@RestController
@RequestMapping(AiSkillPaths.SKILLS)
@Tag(name = "AI Skill", description = "声明式 Skill 与不可变版本管理")
public class AiSkillController {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiSkillController.class
  );

  private static final String REQUEST_INVALID = "AI_SKILL_REQUEST_INVALID";

  private static final String OPERATION_CONFLICT =
      "AI_SKILL_OPERATION_CONFLICT";

  private final AiSkillService service;

  private final AiSkillExecutionService executionService;

  /**
   * Creates the Skill controller.
   */
  public AiSkillController(
      final AiSkillService service,
      final AiSkillExecutionService executionService
  ) {
    this.service = service;
    this.executionService = executionService;
  }

  /**
   * Pages Skills in the active platform or tenant context.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.view')"
  )
  @Operation(summary = "分页查询 Skill")
  public Response<?> findAll(final Pageable pageable) {
    return invoke(() -> service.findAll(pageable));
  }

  /**
   * Returns one visible Skill.
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.view')"
  )
  @Operation(summary = "查询 Skill")
  public Response<?> find(@PathVariable("id") final String id) {
    return invoke(() -> service.find(id).orElseThrow(
        () -> new IllegalArgumentException("Skill does not exist")
    ));
  }

  /**
   * Creates a Skill in the active context.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.create')"
  )
  @Operation(summary = "新增 Skill")
  public Response<?> create(@RequestBody final SkillUpsertRequest request) {
    return invoke(() -> service.create(request));
  }

  /**
   * Updates mutable Skill metadata.
   */
  @PutMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.edit')"
  )
  @Operation(summary = "修改 Skill 元数据")
  public Response<?> update(
      @PathVariable("id") final String id,
      @RequestBody final SkillUpsertRequest request
  ) {
    return invoke(() -> service.update(id, request));
  }

  /**
   * Deletes a Skill without versions.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.delete')"
  )
  @Operation(summary = "删除没有版本的 Skill")
  public Response<?> remove(@PathVariable("id") final String id) {
    return invokeVoid(() -> service.remove(id));
  }

  /**
   * Pages immutable versions for one Skill.
   */
  @GetMapping("/{id}/versions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.view')"
  )
  @Operation(summary = "分页查询 Skill 不可变版本")
  public Response<?> versions(
      @PathVariable("id") final String id,
      final Pageable pageable
  ) {
    return invoke(() -> service.findVersions(id, pageable));
  }

  /**
   * Returns one immutable Skill version.
   */
  @GetMapping("/{id}/versions/{versionId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.view')"
  )
  @Operation(summary = "查询 Skill 不可变版本")
  public Response<?> version(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.findVersion(id, versionId).orElseThrow(
        () -> new IllegalArgumentException("Skill version does not exist")
    ));
  }

  /**
   * Creates one immutable, digest-pinned Skill version.
   */
  @PostMapping("/{id}/versions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.versions.manage')"
  )
  @Operation(summary = "创建 Skill 不可变版本")
  public Response<?> createVersion(
      @PathVariable("id") final String id,
      @RequestBody final SkillVersionCreateRequest request
  ) {
    return invoke(() -> service.createVersion(id, request));
  }

  /**
   * Publishes and activates a Skill version.
   */
  @PostMapping("/{id}/versions/{versionId}/publish")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "发布并激活 Skill 版本")
  public Response<?> publish(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.publishVersion(id, versionId));
  }

  /**
   * Deprecates a published Skill version.
   */
  @PostMapping("/{id}/versions/{versionId}/deprecate")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "废弃 Skill 版本")
  public Response<?> deprecate(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.deprecateVersion(id, versionId));
  }

  /**
   * Starts an idempotent execution of the active published version.
   */
  @PostMapping("/{id}/executions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.execute')"
  )
  @Operation(summary = "执行 Skill Workflow")
  public Response<?> execute(
      @PathVariable("id") final String id,
      @RequestBody final SkillExecutionStartRequest request
  ) {
    return invoke(() -> executionService.start(id, request));
  }

  /**
   * Pages durable workflow executions owned by one Skill.
   */
  @GetMapping("/{id}/executions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.view')"
  )
  @Operation(summary = "分页查询 Skill 执行记录")
  public Response<?> executions(
      @PathVariable("id") final String id,
      final Pageable pageable
  ) {
    return invoke(() -> executionService.findAll(id, pageable));
  }

  /**
   * Returns one durable workflow execution with step checkpoints.
   */
  @GetMapping("/{id}/executions/{executionId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.view')"
  )
  @Operation(summary = "查询 Skill 执行详情")
  public Response<?> execution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.find(id, executionId).orElseThrow(
        () -> new IllegalArgumentException("Skill execution does not exist")
    ));
  }

  /**
   * Approves one execution according to its immutable version policy.
   */
  @PostMapping("/{id}/executions/{executionId}/approve")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.approve')"
  )
  @Operation(summary = "审批通过 Skill 执行")
  public Response<?> approveExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false)
      final SkillExecutionDecisionRequest request
  ) {
    return invoke(() -> executionService.approve(id, executionId, request));
  }

  /**
   * Rejects one execution according to its immutable version policy.
   */
  @PostMapping("/{id}/executions/{executionId}/reject")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.approve')"
  )
  @Operation(summary = "驳回 Skill 执行")
  public Response<?> rejectExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false)
      final SkillExecutionDecisionRequest request
  ) {
    return invoke(() -> executionService.reject(id, executionId, request));
  }

  /**
   * Requests a cooperative pause at the next workflow checkpoint.
   */
  @PostMapping("/{id}/executions/{executionId}/pause")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.control')"
  )
  @Operation(summary = "暂停 Skill 执行")
  public Response<?> pauseExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false)
      final SkillExecutionPauseRequest request
  ) {
    return invoke(() -> executionService.pause(id, executionId, request));
  }

  /**
   * Resumes one cooperatively paused workflow execution.
   */
  @PostMapping("/{id}/executions/{executionId}/resume")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.skills.control')"
  )
  @Operation(summary = "恢复 Skill 执行")
  public Response<?> resumeExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.resume(id, executionId));
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return Response.okay(operation.get());
    } catch (IllegalArgumentException ex) {
      return rejected(ex, REQUEST_INVALID, false);
    } catch (IllegalStateException ex) {
      return rejected(ex, OPERATION_CONFLICT, true);
    }
  }

  private Response<?> invokeVoid(final Runnable operation) {
    try {
      operation.run();
      return Response.okay();
    } catch (IllegalArgumentException ex) {
      return rejected(ex, REQUEST_INVALID, false);
    } catch (IllegalStateException ex) {
      return rejected(ex, OPERATION_CONFLICT, true);
    }
  }

  private Response<?> rejected(
      final RuntimeException exception,
      final String errorCode,
      final boolean conflict
  ) {
    LOG.warn("Skill API request rejected with {}", errorCode, exception);
    ResponseEntity.BodyBuilder response = conflict
        ? ResponseEntity.status(409) : ResponseEntity.badRequest();
    return Response.of(response
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("errorCode", errorCode)));
  }
}
