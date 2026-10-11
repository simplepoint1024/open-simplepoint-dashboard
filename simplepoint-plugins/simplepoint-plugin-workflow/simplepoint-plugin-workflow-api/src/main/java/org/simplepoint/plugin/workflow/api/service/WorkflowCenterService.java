package org.simplepoint.plugin.workflow.api.service;

import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CompleteTaskCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionPermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.InstanceDiagram;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimePermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.TaskSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionSummary;

/** Tenant-scoped workflow definition and runtime boundary. */
public interface WorkflowCenterService {

  /** Pages definitions visible in the active tenant. */
  PageResult<DefinitionSummary> definitions(String keyword, int page, int size);

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

  /** Reads the explicit published version used to prepare a start form. */
  RuntimeForm startForm(String definitionId, Integer version);

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

  /** Pages process instances started by the current user. */
  PageResult<ProcessInstanceSummary> startedInstances(String keyword, int page, int size);

  /** Pages process instances copied to the current user. */
  PageResult<ProcessInstanceSummary> copiedInstances(String keyword, int page, int size);

  /** Pages all tenant process instances for authorized operators. */
  PageResult<ProcessInstanceSummary> instances(String keyword, String status, int page, int size);

  /** Reads BPMN source and execution states for an instance. */
  InstanceDiagram diagram(String processInstanceId);

  /** Terminates a running process instance. */
  ProcessInstanceSummary cancel(String processInstanceId, String reason);
}
