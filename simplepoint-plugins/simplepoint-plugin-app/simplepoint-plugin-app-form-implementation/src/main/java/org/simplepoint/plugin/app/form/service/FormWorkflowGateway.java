package org.simplepoint.plugin.app.form.service;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowResubmission;
import org.simplepoint.plugin.workflow.api.service.WorkflowBusinessRuntimeService;
import org.springframework.stereotype.Component;

/** Keeps downstream workflow failures inside APP's established HTTP/domain error vocabulary. */
@Component
public class FormWorkflowGateway {
  private final WorkflowBusinessRuntimeService remote;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormWorkflowGateway(WorkflowBusinessRuntimeService remote) {
    this.remote = remote;
  }

  org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult<
          org.simplepoint.plugin.workflow.api.model.WorkflowModels.BoundDefinition>
      definitions(
          WorkflowInvocationContext context,
          String formId,
          int version,
          String keyword,
          int page,
          int size) {
    return call(
        () ->
            remote.boundDefinitions(
                context, FormSubmissionMapping.PROVIDER, formId, version, keyword, page, size));
  }

  RuntimeForm taskForm(WorkflowInvocationContext context, String taskId) {
    return call(() -> remote.taskForm(context, taskId));
  }

  RuntimeForm startForm(WorkflowInvocationContext context, String definition, int version) {
    return call(() -> remote.startForm(context, definition, version));
  }

  RuntimeForm validateStartForm(
      WorkflowInvocationContext context,
      String definition,
      int version,
      Map<String, Object> variables,
      List<String> ccUsers) {
    return call(() -> remote.validateStartForm(context, definition, version, variables, ccUsers));
  }

  ProcessInstanceSummary start(
      WorkflowInvocationContext context, String definition, StartProcessCommand command) {
    return call(() -> remote.start(context, definition, command));
  }

  ProcessInstanceSummary resubmit(
      WorkflowInvocationContext context, String previous, StartProcessCommand command) {
    return call(() -> remote.resubmit(context, previous, command));
  }

  ProcessInstanceSummary instance(WorkflowInvocationContext context, String instance) {
    return call(() -> remote.instance(context, instance));
  }

  WorkflowResubmission resubmissionForm(WorkflowInvocationContext context, String instance) {
    return call(() -> remote.resubmissionForm(context, instance));
  }

  private static <T> T call(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (WorkflowException failure) {
      var code =
          switch (failure.getCode()) {
            case FORBIDDEN -> ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED;
            case NOT_FOUND -> ApplicationFormErrorCode.RECORD_NOT_FOUND;
            case CONFLICT -> ApplicationFormErrorCode.RECORD_CONFLICT;
            case VALIDATION -> ApplicationFormErrorCode.RECORD_INVALID;
            case ENGINE_FAILURE -> ApplicationFormErrorCode.RECORD_OPERATION_FAILED;
          };
      throw new ApplicationFormException(code, failure.getMessage(), failure);
    } catch (RuntimeException unavailable) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_OPERATION_FAILED,
          "Workflow service is temporarily unavailable",
          unavailable);
    }
  }
}
