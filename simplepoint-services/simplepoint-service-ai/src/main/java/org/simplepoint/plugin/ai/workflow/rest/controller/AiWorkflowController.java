package org.simplepoint.plugin.ai.workflow.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.simplepoint.plugin.ai.workflow.api.constants.AiWorkflowPaths;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionPauseRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowExecutionStartRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowHumanTaskResponseRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowUpsertRequest;
import org.simplepoint.plugin.ai.workflow.api.model.WorkflowVersionCreateRequest;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowExecutionService;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * AI workbench endpoints for Workflow definitions and immutable versions.
 */
@RestController
@RequestMapping(AiWorkflowPaths.WORKBENCH)
@Tag(name = "AI Workflow", description = "Agent Workflow 与不可变版本管理")
public class AiWorkflowController {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiWorkflowController.class
  );

  private static final String REQUEST_INVALID =
      "AI_WORKFLOW_REQUEST_INVALID";

  private static final String OPERATION_CONFLICT =
      "AI_WORKFLOW_OPERATION_CONFLICT";

  static final Map<String, String> WORKBENCH_PERMISSION_AUTHORITIES =
      Map.ofEntries(
          Map.entry("view", "ai.workbench.workflows.view"),
          Map.entry("create", "ai.workbench.workflows.create"),
          Map.entry("edit", "ai.workbench.workflows.edit"),
          Map.entry("delete", "ai.workbench.workflows.delete"),
          Map.entry(
              "manageVersions",
              "ai.workbench.workflows.versions.manage"
          ),
          Map.entry("publish", "ai.workbench.workflows.publish"),
          Map.entry("execute", "ai.workbench.workflows.execute"),
          Map.entry("intervene", "ai.workbench.workflows.intervene")
      );

  private static final String WORKBENCH_PERMISSION_AUTHORIZATION =
      "hasRole('Administrator') or hasAnyAuthority("
          + "'ai.workbench.workflows.view', "
          + "'ai.workbench.workflows.create', "
          + "'ai.workbench.workflows.edit', "
          + "'ai.workbench.workflows.delete', "
          + "'ai.workbench.workflows.versions.manage', "
          + "'ai.workbench.workflows.publish', "
          + "'ai.workbench.workflows.execute', "
          + "'ai.workbench.workflows.intervene')";

  private final AiWorkflowService service;

  private final AiWorkflowExecutionService executionService;

  /**
   * Creates the Workflow controller.
   */
  public AiWorkflowController(
      final AiWorkflowService service,
      final AiWorkflowExecutionService executionService
  ) {
    this.service = service;
    this.executionService = executionService;
  }

  /**
   * Pages Workflows in the active platform or tenant context.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.workflows.view')"
  )
  @Operation(summary = "分页查询 Workflow")
  public Response<?> findAll(final Pageable pageable) {
    return invoke(() -> service.findAll(pageable));
  }

  /**
   * Returns the current subject's Workflow workbench action permissions.
   */
  @GetMapping("/workbench-permissions")
  @PreAuthorize(WORKBENCH_PERMISSION_AUTHORIZATION)
  @Operation(summary = "查询 Workflow 工作台功能权限")
  public Response<?> workbenchPermissions(
      final Authentication authentication
  ) {
    return Response.okay(resolveWorkbenchPermissions(authentication));
  }

  /**
   * Searches selectable Agent or Skill dependencies for one Workflow.
   */
  @GetMapping("/{id}/dependency-options")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.versions.manage')"
  )
  @Operation(summary = "分页查询 Workflow 可选依赖")
  public Response<?> dependencyOptions(
      @PathVariable("id") final String id,
      @RequestParam("kind") final String kind,
      @RequestParam(value = "q", defaultValue = "") final String query,
      @RequestParam(value = "page", defaultValue = "0") final int page,
      @RequestParam(value = "size", defaultValue = "20") final int size
  ) {
    return invoke(() -> service.findDependencyOptions(
        id,
        dependencyKind(kind),
        query,
        page,
        size
    ));
  }

  /**
   * Resolves current or historical Agent and Skill dependency labels.
   */
  @GetMapping("/{id}/dependency-options/resolve")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.versions.manage')"
  )
  @Operation(summary = "解析 Workflow 已选依赖")
  public Response<?> resolveDependencyOptions(
      @PathVariable("id") final String id,
      @RequestParam("kind") final String kind,
      @RequestParam("ids") final String ids
  ) {
    return invoke(() -> service.resolveDependencyOptions(
        id,
        dependencyKind(kind),
        List.of(ids.split(",", -1))
    ));
  }

  /**
   * Returns one visible Workflow.
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.workflows.view')"
  )
  @Operation(summary = "查询 Workflow")
  public Response<?> find(@PathVariable("id") final String id) {
    return invoke(() -> service.find(id).orElseThrow(
        () -> new IllegalArgumentException("Workflow does not exist")
    ));
  }

  /**
   * Creates one scope-owned Workflow.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.create')"
  )
  @Operation(summary = "新增 Workflow")
  public Response<?> create(
      @RequestBody final WorkflowUpsertRequest request
  ) {
    return invoke(() -> service.create(request));
  }

  /**
   * Updates mutable Workflow metadata.
   */
  @PutMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.edit')"
  )
  @Operation(summary = "修改 Workflow 元数据")
  public Response<?> update(
      @PathVariable("id") final String id,
      @RequestBody final WorkflowUpsertRequest request
  ) {
    return invoke(() -> service.update(id, request));
  }

  /**
   * Removes a Workflow without immutable versions.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.delete')"
  )
  @Operation(summary = "删除没有版本的 Workflow")
  public Response<?> remove(@PathVariable("id") final String id) {
    return invokeVoid(() -> service.remove(id));
  }

  /**
   * Pages immutable versions.
   */
  @GetMapping("/{id}/versions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.workflows.view')"
  )
  @Operation(summary = "分页查询 Workflow 版本")
  public Response<?> versions(
      @PathVariable("id") final String id,
      final Pageable pageable
  ) {
    return invoke(() -> service.findVersions(id, pageable));
  }

  /**
   * Returns one immutable version.
   */
  @GetMapping("/{id}/versions/{versionId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.workflows.view')"
  )
  @Operation(summary = "查询 Workflow 版本")
  public Response<?> version(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.findVersion(id, versionId).orElseThrow(
        () -> new IllegalArgumentException(
            "Workflow version does not exist"
        )
    ));
  }

  /**
   * Creates an immutable declarative version.
   */
  @PostMapping("/{id}/versions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.versions.manage')"
  )
  @Operation(summary = "创建 Workflow 不可变版本")
  public Response<?> createVersion(
      @PathVariable("id") final String id,
      @RequestBody final WorkflowVersionCreateRequest request
  ) {
    return invoke(() -> service.createVersion(id, request));
  }

  /**
   * Publishes and activates one version.
   */
  @PostMapping("/{id}/versions/{versionId}/publish")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.publish')"
  )
  @Operation(summary = "发布并激活 Workflow 版本")
  public Response<?> publish(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.publishVersion(id, versionId));
  }

  /**
   * Deprecates a published version.
   */
  @PostMapping("/{id}/versions/{versionId}/deprecate")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.publish')"
  )
  @Operation(summary = "废弃 Workflow 版本")
  public Response<?> deprecate(
      @PathVariable("id") final String id,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.deprecateVersion(id, versionId));
  }

  /**
   * Starts an idempotent execution of the active version.
   */
  @PostMapping("/{id}/executions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.execute')"
  )
  @Operation(summary = "执行 Workflow")
  public Response<?> execute(
      @PathVariable("id") final String id,
      @RequestBody final WorkflowExecutionStartRequest request
  ) {
    return invoke(() -> executionService.start(id, request));
  }

  /**
   * Pages executions in the active scope.
   */
  @GetMapping("/{id}/executions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.workflows.view')"
  )
  @Operation(summary = "分页查询 Workflow 执行")
  public Response<?> executions(
      @PathVariable("id") final String id,
      final Pageable pageable
  ) {
    return invoke(() -> executionService.findAll(id, pageable));
  }

  /**
   * Returns a durable execution with node checkpoints and human tasks.
   */
  @GetMapping("/{id}/executions/{executionId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.workflows.view')"
  )
  @Operation(summary = "查询 Workflow 执行详情")
  public Response<?> execution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.find(id, executionId).orElseThrow(
        () -> new IllegalArgumentException(
            "Workflow execution does not exist"
        )
    ));
  }

  /**
   * Reads the append-only event feed after a sequence cursor.
   */
  @GetMapping("/{id}/executions/{executionId}/events")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.workflows.view')"
  )
  @Operation(summary = "查询 Workflow 执行事件")
  public Response<?> executionEvents(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestParam(name = "after", required = false) final Long after,
      @RequestParam(name = "limit", required = false) final Integer limit
  ) {
    return invoke(() -> executionService.findEvents(
        id,
        executionId,
        after == null ? 0L : Math.max(0L, after),
        limit == null ? 100 : limit
    ));
  }

  /**
   * Requests a cooperative pause.
   */
  @PostMapping("/{id}/executions/{executionId}/pause")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.intervene')"
  )
  @Operation(summary = "暂停 Workflow 执行")
  public Response<?> pauseExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @RequestBody final WorkflowExecutionPauseRequest request
  ) {
    return invoke(() -> executionService.pause(id, executionId, request));
  }

  /**
   * Resumes a cooperatively paused execution.
   */
  @PostMapping("/{id}/executions/{executionId}/resume")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.intervene')"
  )
  @Operation(summary = "恢复 Workflow 执行")
  public Response<?> resumeExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.resume(id, executionId));
  }

  /**
   * Cancels a non-terminal execution.
   */
  @PostMapping("/{id}/executions/{executionId}/cancel")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.intervene')"
  )
  @Operation(summary = "取消 Workflow 执行")
  public Response<?> cancelExecution(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId
  ) {
    return invoke(() -> executionService.cancel(id, executionId));
  }

  /**
   * Completes one open structured human task.
   */
  @PostMapping(
      "/{id}/executions/{executionId}/human-tasks/{taskId}/respond"
  )
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.workflows.intervene')"
  )
  @Operation(summary = "处理 Workflow 人工任务")
  public Response<?> respondHumanTask(
      @PathVariable("id") final String id,
      @PathVariable("executionId") final String executionId,
      @PathVariable("taskId") final String taskId,
      @RequestBody final WorkflowHumanTaskResponseRequest request
  ) {
    return invoke(() -> executionService.respondHumanTask(
        id,
        executionId,
        taskId,
        request
    ));
  }

  static Map<String, Boolean> resolveWorkbenchPermissions(
      final Authentication authentication
  ) {
    Collection<? extends GrantedAuthority> grantedAuthorities =
        authentication == null || !authentication.isAuthenticated()
            ? Set.of() : authentication.getAuthorities();
    Set<String> authoritySnapshot = grantedAuthorities == null
        ? Set.of() : grantedAuthorities.stream()
            .filter(Objects::nonNull)
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority != null)
            .collect(Collectors.toUnmodifiableSet());
    boolean administrator = authoritySnapshot.contains(
        "ROLE_Administrator"
    );
    return WORKBENCH_PERMISSION_AUTHORITIES.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            entry -> administrator
                || authoritySnapshot.contains(entry.getValue())
        ));
  }

  private static AiDependencyKind dependencyKind(final String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Dependency kind must not be blank"
      );
    }
    try {
      return AiDependencyKind.valueOf(value.trim());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "Dependency kind is invalid",
          ex
      );
    }
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
    LOG.warn("Workflow API request rejected with {}", errorCode, exception);
    ResponseEntity.BodyBuilder response = conflict
        ? ResponseEntity.status(409) : ResponseEntity.badRequest();
    return Response.of(response
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("errorCode", errorCode)));
  }
}
