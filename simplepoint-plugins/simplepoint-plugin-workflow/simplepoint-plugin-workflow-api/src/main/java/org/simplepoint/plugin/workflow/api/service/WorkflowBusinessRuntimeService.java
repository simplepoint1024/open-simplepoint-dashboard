package org.simplepoint.plugin.workflow.api.service;

import org.simplepoint.plugin.workflow.api.WorkflowErrorDecoder;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.remoting.RemoteContract;

/** Narrow trusted business-engine boundary; it grants no definition administration authority. */
@RemoteContract(
    name = "workflow.business-runtime",
    version = "1",
    errorDecoder = WorkflowErrorDecoder.class)
public interface WorkflowBusinessRuntimeService {
  /** Pages published process definitions bound to the exact authorized form version. */
  org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult<
          org.simplepoint.plugin.workflow.api.model.WorkflowModels.BoundDefinition>
      boundDefinitions(
          WorkflowInvocationContext context,
          String provider,
          String formId,
          int formVersion,
          String keyword,
          int page,
          int size);

  /** Reads the current task form after checking the effective actor and task participation. */
  RuntimeForm taskForm(WorkflowInvocationContext context, String taskId);

  /** Reads the authorized starter contract for a pinned process version. */
  RuntimeForm startForm(WorkflowInvocationContext context, String definitionId, Integer version);

  /** Validates starter values and directory identities without creating a process. */
  RuntimeForm validateStartForm(
      WorkflowInvocationContext context,
      String definitionId,
      int version,
      java.util.Map<String, Object> variables,
      java.util.List<String> ccUserIds);

  /** Starts one pinned business submission using its durable operation key. */
  ProcessInstanceSummary start(
      WorkflowInvocationContext context, String definitionId, StartProcessCommand command);

  /** Creates a linked new round while retaining the original definition version and evidence. */
  ProcessInstanceSummary resubmit(
      WorkflowInvocationContext context, String previousInstanceId, StartProcessCommand command);

  /** Returns the original contract only when this actor can resubmit the terminal round. */
  org.simplepoint.plugin.workflow.api.model.WorkflowResubmission resubmissionForm(
      WorkflowInvocationContext context, String processInstanceId);

  /** The same tenant and participant boundary used by the workflow progress page. */
  ProcessInstanceSummary instance(WorkflowInvocationContext context, String processInstanceId);
}
