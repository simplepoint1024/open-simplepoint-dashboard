package org.simplepoint.workflow.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.api.model.WorkflowAuditTrail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.InstanceDiagram;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimePermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.TaskSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.NodeExecution;
import org.simplepoint.plugin.workflow.api.model.WorkflowResubmission;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Process start, work item, history and diagram endpoints. */
@RestController
@RequestMapping
@Tag(name = "流程运行时", description = "流程实例和用户任务")
public class WorkflowRuntimeController {

  private final WorkflowCenterService service;

  /** Creates the workflow runtime endpoint adapter. */
  public WorkflowRuntimeController(final WorkflowCenterService service) {
    this.service = service;
  }

  /** Returns current task and instance capabilities. */
  @GetMapping("/runtime/permissions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAnyAuthority('workflow.tasks.view',"
          + " 'workflow.instances.started.view', 'workflow.instances.view')")
  @Operation(summary = "查询当前运行时操作权限")
  public Response<RuntimePermissions> permissions() {
    return Response.okay(service.runtimePermissions());
  }

  /** Starts an instance from a published process version. */
  @PostMapping("/definitions/{id}/instances")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.start')")
  @Operation(summary = "按已发布版本启动流程")
  public Response<ProcessInstanceSummary> start(
      @PathVariable("id") final String definitionId,
      @RequestBody(required = false) final StartProcessCommand command) {
    return Response.okay(service.start(definitionId, command));
  }

  /** Returns version-pinned form metadata to an authorized process starter. */
  @GetMapping("/definitions/{id}/start-form")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.start')")
  public Response<RuntimeForm> startForm(
      @PathVariable("id") final String definitionId,
      @RequestParam(name = "version", required = false) final Integer version) {
    return Response.okay(service.startForm(definitionId, version));
  }

  /** Loads original-version values for an authorized linked resubmission. */
  @GetMapping("/instances/{id}/resubmission-form")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.start')")
  public Response<WorkflowResubmission> resubmissionForm(@PathVariable("id") String id) {
    return Response.okay(service.resubmissionForm(id));
  }

  /** Starts a new linked instance without rewriting the previous round. */
  @PostMapping("/instances/{id}/resubmit")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.start')")
  public Response<ProcessInstanceSummary> resubmit(
      @PathVariable("id") String id, @RequestBody StartProcessCommand command) {
    return Response.okay(service.resubmit(id, command));
  }

  /** Returns a task form after checking assignment/candidacy and tenant ownership. */
  @GetMapping("/tasks/{id}/form")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.tasks.complete')")
  public Response<RuntimeForm> taskForm(@PathVariable("id") final String taskId) {
    return Response.okay(service.taskForm(taskId));
  }

  /** Pages pending tasks for the current user. */
  @GetMapping("/tasks/pending")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.tasks.view')")
  @Operation(summary = "查询我的待办任务")
  public Response<PageResult<TaskSummary>> pending(
      @RequestParam(name = "keyword", required = false) final String keyword,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size) {
    return Response.okay(service.pendingTasks(keyword, page, size));
  }

  /** Pages completed tasks for the current user. */
  @GetMapping("/tasks/completed")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.tasks.view')")
  @Operation(summary = "查询我已处理的任务")
  public Response<PageResult<TaskSummary>> completed(
      @RequestParam(name = "keyword", required = false) final String keyword,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size) {
    return Response.okay(service.completedTasks(keyword, page, size));
  }

  /** Claims a candidate task. */
  @PostMapping("/tasks/{id}/claim")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.tasks.claim')")
  @Operation(summary = "认领候选任务")
  public Response<TaskSummary> claim(@PathVariable("id") final String id) {
    return Response.okay(service.claim(id));
  }

  /** Completes an assigned or claimable task. */
  @PostMapping("/tasks/{id}/complete")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.tasks.complete')")
  @Operation(summary = "提交并完成任务")
  public Response<ProcessInstanceSummary> complete(
      @PathVariable("id") final String id,
      @RequestBody(required = false) final CompleteTaskCommand command) {
    return Response.okay(service.complete(id, command));
  }

  /** Rejects a task within exactly the same assignment boundary as approval. */
  @PostMapping("/tasks/{id}/reject")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.tasks.complete')")
  @Operation(summary = "驳回当前审批并结束本轮流程")
  public Response<ProcessInstanceSummary> reject(
      @PathVariable("id") final String id, @RequestBody final CancelCommand command) {
    return Response.okay(service.reject(id, command == null ? null : command.reason(), command == null ? null : command.idempotencyKey()));
  }

  /** Withdrawal is a starter operation; the domain never grants it to a different actor. */
  @PostMapping("/instances/{id}/withdraw")
  @PreAuthorize(
      "hasRole('Administrator') or hasAnyAuthority('workflow.instances.started.view',"
          + " 'workflow.instances.view')")
  @Operation(summary = "发起人撤回尚未结束的流程")
  public Response<ProcessInstanceSummary> withdraw(
      @PathVariable("id") final String id, @RequestBody final CancelCommand command) {
    return Response.okay(service.withdraw(id, command == null ? null : command.reason(), command == null ? null : command.idempotencyKey()));
  }

  /** Pages matters started by the current user. */
  @GetMapping("/instances/started")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.started.view')")
  @Operation(summary = "查询我发起的事项")
  public Response<PageResult<ProcessInstanceSummary>> started(
      @RequestParam(name = "keyword", required = false) final String keyword,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size) {
    return Response.okay(service.startedInstances(keyword, page, size));
  }

  /** Pages matters copied to the current user. */
  @GetMapping("/instances/copied")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.started.view')")
  @Operation(summary = "查询抄送给我的事项")
  public Response<PageResult<ProcessInstanceSummary>> copied(
      @RequestParam(name = "keyword", required = false) final String keyword,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size) {
    return Response.okay(service.copiedInstances(keyword, page, size));
  }

  /** Pages tenant instances for workflow managers. */
  @GetMapping("/instances")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.view')")
  @Operation(summary = "分页查询租户流程实例")
  public Response<PageResult<ProcessInstanceSummary>> instances(
      @RequestParam(name = "keyword", required = false) final String keyword,
      @RequestParam(name = "status", required = false) final String status,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size) {
    return Response.okay(service.instances(keyword, status, page, size));
  }

  /** Reads the authorized execution-state diagram. */
  @GetMapping("/instances/{id}/diagram")
  @PreAuthorize(
      "hasRole('Administrator') or hasAnyAuthority('workflow.instances.view',"
          + " 'workflow.instances.started.view', 'workflow.tasks.view')")
  @Operation(summary = "读取流程实例状态图")
  public Response<InstanceDiagram> diagram(@PathVariable("id") final String id) {
    return Response.okay(service.diagram(id));
  }

  /** Returns the matter projection and bounded process diagram. */
  @GetMapping("/instances/{id}/progress")
  @PreAuthorize(
      "hasRole('Administrator') or hasAnyAuthority('workflow.instances.view',"
          + " 'workflow.instances.started.view', 'workflow.tasks.view')")
  public Response<WorkflowProgress> progress(@PathVariable("id") String id) {
    return Response.okay(service.progress(id));
  }

  /** Pages actual executions and approval details for one BPMN node. */
  @GetMapping("/instances/{id}/nodes/{nodeId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAnyAuthority('workflow.instances.view',"
          + " 'workflow.instances.started.view', 'workflow.tasks.view')")
  public Response<PageResult<NodeExecution>> nodeHistory(
      @PathVariable("id") String id,
      @PathVariable("nodeId") String nodeId,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size,
      @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly) {
    return Response.okay(service.nodeHistory(id, nodeId, page, size, activeOnly));
  }

  /** Pages immutable platform operations separately from engine activities. */
  @GetMapping("/instances/{id}/actions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAnyAuthority('workflow.tasks.view',"
          + " 'workflow.instances.started.view', 'workflow.instances.view')")
  public Response<WorkflowAuditTrail> actions(
      @PathVariable("id") String id,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return Response.okay(service.auditTrail(id, page, size));
  }

  /** Cancels a running instance within the actor's permitted scope. */
  @PostMapping("/instances/{id}/cancel")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.cancel')")
  @Operation(summary = "终止运行中的流程实例")
  public Response<ProcessInstanceSummary> cancel(
      @PathVariable("id") final String id, @RequestBody final CancelCommand command) {
    return Response.okay(service.cancel(id, command == null ? null : command.reason(), command == null ? null : command.idempotencyKey()));
  }

  /** Cancellation request with an audited reason. */
  public record CancelCommand(String reason, String idempotencyKey) {
    public CancelCommand(String reason) { this(reason, null); }
  }
}
