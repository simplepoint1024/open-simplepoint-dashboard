package org.simplepoint.plugin.workflow.api.service;

import org.simplepoint.plugin.workflow.api.model.WorkflowAuditTrail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionPermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.InstanceDiagram;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimePermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.TaskSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress;
import org.simplepoint.plugin.workflow.api.model.WorkflowProgress.NodeExecution;
import org.simplepoint.plugin.workflow.api.model.WorkflowResubmission;

/** Tenant-scoped workflow definition and runtime boundary. */
public interface WorkflowCenterService {

  /** Bounded instance action history, protected by the same participant boundary as progress. */
  WorkflowAuditTrail auditTrail(String processInstanceId, int page, int size);

  /** Reads only the original starter's eligible terminal form, pinned to its published version. */
  WorkflowResubmission resubmissionForm(String processInstanceId);

  /** Creates one linked successor; source history is never reopened or overwritten. */
  ProcessInstanceSummary resubmit(String processInstanceId, StartProcessCommand command);

  /** Pages definitions visible in the active tenant. */
  PageResult<DefinitionSummary> definitions(String keyword, int page, int size);

  /** Allowlisted server-side header filtering and stable multi-column sorting. */
  PageResult<DefinitionSummary> queryDefinitions(
      java.util.Map<String, String> filters, org.springframework.data.domain.Pageable page);

  /** Same field budget as queryDefinitions, without exposing draft payloads or actor IDs. */
  java.util.Map<String, Object> definitionsSchema();

  /** Resolves current definition and start operation permissions. */
  DefinitionPermissions definitionPermissions();

  /** Resolves current task and instance operation permissions. */
  RuntimePermissions runtimePermissions();

  /** Creates a process definition and starter draft. */
  DefinitionDetail createDefinition(CreateDefinitionCommand command);

  /** Reads a definition editor projection. */
  DefinitionDetail definition(String definitionId);

  /** Saves a draft using optimistic revision control. */
  DefinitionDetail updateDraft(String definitionId, UpdateDraftCommand command);

  /** Publishes an immutable Flowable-backed version. */
  VersionSummary publish(String definitionId, PublishDefinitionCommand command);

  /** Reads an immutable published version. */
  VersionDetail version(String definitionId, int version);

  /** Copies a published version into the mutable draft. */
  DefinitionDetail copyVersionToDraft(String definitionId, int version, long expectedRevision);

  /** Starts a process instance. */
  ProcessInstanceSummary start(String definitionId, StartProcessCommand command);

  /** Pages published process definitions bound to the exact authorized form version. */
  PageResult<org.simplepoint.plugin.workflow.api.model.WorkflowModels.BoundDefinition>
      boundDefinitions(
          String provider, String formId, int formVersion, String keyword, int page, int size);

  /** Reads the explicit published version used to prepare a start form. */
  RuntimeForm startForm(String definitionId, Integer version);

  /** Preflights business values and directory identities without creating engine state. */
  RuntimeForm validateStartForm(
      String definitionId,
      int version,
      java.util.Map<String, Object> variables,
      java.util.List<String> ccUserIds);

  /** Reads a task's authorized form from its instance's pinned definition version. */
  RuntimeForm taskForm(String taskId);

  /** Pages current-user pending tasks. */
  PageResult<TaskSummary> pendingTasks(String keyword, int page, int size);

  /** Pages tasks completed by the current user. */
  PageResult<TaskSummary> completedTasks(String keyword, int page, int size);

  /** Claims a candidate task for the current user. */
  TaskSummary claim(String taskId);

  /** Completes a task and returns its parent instance. */
  ProcessInstanceSummary complete(String taskId, CompleteTaskCommand command);

  /** Rejects the current approval and ends this instance, preserving its history. */
  ProcessInstanceSummary reject(String taskId, String reason);

  /** Retries the exact actor-owned decision without advancing the engine twice. */
  ProcessInstanceSummary reject(String taskId, String reason, String idempotencyKey);

  /** Lets only the original starter withdraw a still-running instance. */
  ProcessInstanceSummary withdraw(String processInstanceId, String reason);

  /** Retries the exact actor-owned decision without advancing the engine twice. */
  ProcessInstanceSummary withdraw(String processInstanceId, String reason, String idempotencyKey);

  /** Pages process instances started by the current user. */
  PageResult<ProcessInstanceSummary> startedInstances(String keyword, int page, int size);

  /** Pages process instances copied to the current user. */
  PageResult<ProcessInstanceSummary> copiedInstances(String keyword, int page, int size);

  /** Pages all tenant process instances for authorized operators. */
  PageResult<ProcessInstanceSummary> instances(String keyword, String status, int page, int size);

  /** Reads BPMN source and execution states for an instance. */
  InstanceDiagram diagram(String processInstanceId);

  /** Personal progress header plus immutable source and execution states. */
  WorkflowProgress progress(String processInstanceId);

  /** Pages distinct executions of one BPMN node after verifying instance visibility. */
  PageResult<NodeExecution> nodeHistory(
      String processInstanceId, String activityId, int page, int size, boolean activeOnly);

  /** Compatibility overload for the complete node execution timeline. */
  default PageResult<NodeExecution> nodeHistory(
      String processInstanceId, String activityId, int page, int size) {
    return nodeHistory(processInstanceId, activityId, page, size, false);
  }

  /** Terminates a running process instance. */
  ProcessInstanceSummary cancel(String processInstanceId, String reason);

  /** Retries the exact actor-owned decision without advancing the engine twice. */
  ProcessInstanceSummary cancel(String processInstanceId, String reason, String idempotencyKey);
}
