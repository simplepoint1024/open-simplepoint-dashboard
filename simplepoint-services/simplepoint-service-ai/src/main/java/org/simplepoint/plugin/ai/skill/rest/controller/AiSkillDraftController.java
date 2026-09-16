package org.simplepoint.plugin.ai.skill.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.Supplier;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.skill.api.constants.AiSkillPaths;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftDebugExecutionStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftError;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftMockTestRunStartRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRestoreRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftSaveRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionBreakpointsRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionCancelRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillExecutionPauseRequest;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillDraftService;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillExecutionService;
import org.simplepoint.plugin.ai.skill.api.service.SkillDraftConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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

/** HTTP lifecycle for mutable Skill Designer Drafts. */
@RestController
@RequestMapping(AiSkillPaths.SKILLS + "/{skillId}/draft")
@Tag(name = "AI Skill Draft", description = "Skill 可视化设计草稿与修订管理")
public class AiSkillDraftController {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiSkillDraftController.class
  );

  private static final String REVISION_CONFLICT =
      "SKILL_DRAFT_REVISION_CONFLICT";

  private static final String REQUEST_INVALID =
      "SKILL_DRAFT_REQUEST_INVALID";

  private final AiSkillDraftService service;

  private final AiSkillExecutionService executionService;

  /** Creates the Skill Draft controller. */
  public AiSkillDraftController(
      final AiSkillDraftService service,
      final AiSkillExecutionService executionService
  ) {
    this.service = service;
    this.executionService = executionService;
  }

  /** Starts a LIVE execution pinned to one immutable Draft Revision. */
  @PostMapping("/debug-executions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "启动 Skill 固定草稿修订调试")
  public Response<?> startDebugExecution(
      @PathVariable("skillId") final String skillId,
      @RequestBody final SkillDraftDebugExecutionStartRequest request
  ) {
    return invoke(() -> executionService.startDraftDebug(skillId, request));
  }

  /** Starts all enabled Mock cases from one immutable Draft Revision. */
  @PostMapping("/mock-test-runs")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "启动 Skill 草稿 MOCK 批量回归")
  public Response<?> startMockTestRun(
      @PathVariable("skillId") final String skillId,
      @RequestBody final SkillDraftMockTestRunStartRequest request
  ) {
    return invoke(() -> executionService.startDraftMockTestRun(
        skillId,
        request
    ));
  }

  /** Pages durable Mock regression runs. */
  @GetMapping("/mock-test-runs")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "分页查询 Skill 草稿 MOCK 回归记录")
  public Response<?> mockTestRuns(
      @PathVariable("skillId") final String skillId,
      final Pageable pageable
  ) {
    return invoke(() -> executionService.findAllDraftMockTestRuns(
        skillId,
        pageable
    ));
  }

  /** Returns one durable Mock regression run and ordered case summaries. */
  @GetMapping("/mock-test-runs/{testRunId}")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "查询 Skill 草稿 MOCK 回归详情")
  public Response<?> mockTestRun(
      @PathVariable("skillId") final String skillId,
      @PathVariable("testRunId") final String testRunId
  ) {
    try {
      return executionService.findDraftMockTestRun(skillId, testRunId)
          .<Response<?>>map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return badRequest(exception);
    }
  }

  /** Pages durable Draft debug executions. */
  @GetMapping("/debug-executions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "分页查询 Skill 草稿调试执行")
  public Response<?> debugExecutions(
      @PathVariable("skillId") final String skillId,
      final Pageable pageable
  ) {
    return invoke(() ->
        executionService.findAllDraftDebug(skillId, pageable));
  }

  /** Returns one durable Draft debug execution and its checkpoints. */
  @GetMapping("/debug-executions/{executionId}")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "查询 Skill 草稿调试执行详情")
  public Response<?> debugExecution(
      @PathVariable("skillId") final String skillId,
      @PathVariable("executionId") final String executionId
  ) {
    try {
      return executionService.findDraftDebug(skillId, executionId)
          .<Response<?>>map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return badRequest(exception);
    }
  }

  /** Reads durable debug events using an exclusive sequence cursor. */
  @GetMapping("/debug-executions/{executionId}/events")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "增量查询 Skill 草稿调试执行事件")
  public Response<?> debugExecutionEvents(
      @PathVariable("skillId") final String skillId,
      @PathVariable("executionId") final String executionId,
      @RequestParam(name = "afterSequence", required = false)
      final Long afterSequence,
      @RequestParam(name = "after", required = false)
      final Long legacyAfter,
      @RequestParam(name = "limit", defaultValue = "100")
      final int limit
  ) {
    final Long requestedCursor = afterSequence == null
        ? legacyAfter : afterSequence;
    final long cursor = requestedCursor == null ? 0L : requestedCursor;
    return invoke(() -> executionService.findDraftDebugEvents(
        skillId,
        executionId,
        cursor,
        limit
    ));
  }

  /** Requests a safe pause for one Draft debug execution. */
  @PostMapping("/debug-executions/{executionId}/pause")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "在安全检查点暂停 Draft 调试")
  public Response<?> pauseDebugExecution(
      @PathVariable("skillId") final String skillId,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false) final SkillExecutionPauseRequest request
  ) {
    return invoke(() -> {
      requireDraftDebugExecution(skillId, executionId);
      return executionService.pause(skillId, executionId, request);
    });
  }

  /** Continues one safely paused Draft debug execution. */
  @PostMapping("/debug-executions/{executionId}/continue")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "继续 Draft 调试")
  public Response<?> continueDebugExecution(
      @PathVariable("skillId") final String skillId,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> {
      requireDraftDebugExecution(skillId, executionId);
      return executionService.resume(skillId, executionId);
    });
  }

  /** Cooperatively cancels one Draft debug execution. */
  @PostMapping("/debug-executions/{executionId}/cancel")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "协作式取消 Draft 调试")
  public Response<?> cancelDebugExecution(
      @PathVariable("skillId") final String skillId,
      @PathVariable("executionId") final String executionId,
      @RequestBody(required = false) final SkillExecutionCancelRequest request
  ) {
    return invoke(() -> {
      requireDraftDebugExecution(skillId, executionId);
      return executionService.cancel(skillId, executionId, request);
    });
  }

  /** Replaces safe pre-step breakpoints for one Draft debug execution. */
  @PutMapping("/debug-executions/{executionId}/breakpoints")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.debug')"
  )
  @Operation(summary = "配置 Draft 调试安全断点")
  public Response<?> setDebugBreakpoints(
      @PathVariable("skillId") final String skillId,
      @PathVariable("executionId") final String executionId,
      @RequestBody final SkillExecutionBreakpointsRequest request
  ) {
    return invoke(() -> executionService.setDraftDebugBreakpoints(
        skillId,
        executionId,
        request
    ));
  }

  private void requireDraftDebugExecution(
      final String skillId,
      final String executionId
  ) {
    executionService.findDraftDebug(skillId, executionId)
        .orElseThrow(() -> new IllegalArgumentException(
            "Skill Draft debug execution does not exist"
        ));
  }

  /** Returns the current mutable Draft. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.view')"
  )
  @Operation(summary = "查询 Skill 设计草稿")
  public Response<?> find(
      @PathVariable("skillId") final String skillId
  ) {
    try {
      return service.find(skillId)
          .<Response<?>>map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return badRequest(exception);
    }
  }

  /** Creates or replaces the current Draft. */
  @PutMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.manage')"
  )
  @Operation(summary = "保存 Skill 设计草稿")
  public Response<?> save(
      @PathVariable("skillId") final String skillId,
      @RequestBody final SkillDraftSaveRequest request
  ) {
    return invoke(() -> service.save(skillId, request));
  }

  /** Soft deletes the current Draft. */
  @DeleteMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.manage')"
  )
  @Operation(summary = "删除 Skill 设计草稿")
  public Response<?> remove(
      @PathVariable("skillId") final String skillId,
      @RequestParam("expectedRevision") final long expectedRevision
  ) {
    return invoke(() -> {
      service.remove(skillId, expectedRevision);
      return skillId;
    });
  }

  /** Pages immutable Draft Revisions newest first. */
  @GetMapping("/revisions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.view')"
  )
  @Operation(summary = "分页查询 Skill 草稿修订")
  public Response<?> revisions(
      @PathVariable("skillId") final String skillId,
      final Pageable pageable
  ) {
    return invoke(() -> service.findRevisions(skillId, pageable));
  }

  /** Restores a historical snapshot as a new revision. */
  @PostMapping("/revisions/{revision}/restore")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.manage')"
  )
  @Operation(summary = "恢复 Skill 历史草稿修订")
  public Response<?> restore(
      @PathVariable("skillId") final String skillId,
      @PathVariable("revision") final long revision,
      @RequestBody final SkillDraftRestoreRequest request
  ) {
    return invoke(() -> service.restore(skillId, revision, request));
  }

  /** Validates the currently saved Draft with structured diagnostics. */
  @PostMapping("/validate")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.manage')"
  )
  @Operation(summary = "校验 Skill 设计草稿")
  public Response<?> validate(
      @PathVariable("skillId") final String skillId
  ) {
    return invoke(() -> service.compile(skillId));
  }

  /** Compiles the currently saved Draft into a canonical Manifest. */
  @PostMapping("/compile")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.manage')"
  )
  @Operation(summary = "编译 Skill 设计草稿")
  public Response<?> compile(
      @PathVariable("skillId") final String skillId
  ) {
    return invoke(() -> service.compile(skillId));
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return Response.okay(operation.get());
    } catch (SkillDraftConflictException exception) {
      LOG.warn("Skill Draft request rejected with {}",
          REVISION_CONFLICT, exception);
      return Response.of(ResponseEntity.status(HttpStatus.CONFLICT)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SkillDraftError(
              REVISION_CONFLICT,
              REVISION_CONFLICT,
              exception.getExpectedRevision(),
              exception.getCurrentRevision()
          )));
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return badRequest(exception);
    }
  }

  private Response<?> badRequest(final RuntimeException exception) {
    LOG.warn("Skill Draft request rejected with {}",
        REQUEST_INVALID, exception);
    return Response.of(ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SkillDraftError(
            REQUEST_INVALID,
            REQUEST_INVALID,
            null,
            null
        )));
  }
}
