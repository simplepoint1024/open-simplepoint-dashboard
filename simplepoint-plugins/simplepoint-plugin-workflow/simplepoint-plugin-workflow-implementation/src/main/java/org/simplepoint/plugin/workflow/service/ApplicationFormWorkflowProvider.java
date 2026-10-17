package org.simplepoint.plugin.workflow.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowBindingCheck;
import org.simplepoint.plugin.app.form.api.model.FormWorkflowSubmissionCheck;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormWorkflowPort;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowFormContract;
import org.simplepoint.plugin.workflow.api.service.WorkflowFormProvider;
import org.springframework.stereotype.Component;

/**
 * Explicit platform integration; external forms never inject scripts, engine services or arbitrary
 * URLs.
 */
@Component
public class ApplicationFormWorkflowProvider implements WorkflowFormProvider {
  private final ApplicationFormWorkflowPort forms;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public ApplicationFormWorkflowProvider(ApplicationFormWorkflowPort forms) {
    this.forms = forms;
  }

  @Override
  public String provider() {
    return "simplepoint-app";
  }

  @Override
  public boolean requiresBusinessReference() {
    return true;
  }

  @Override
  public boolean protectsMappedVariables() {
    return true;
  }

  @Override
  public void validateBinding(
      WorkflowFormContract.Binding binding,
      String stage,
      String taskDefinitionKey,
      WorkflowFormContract contract,
      String tenantId) {
    if ("task".equals(stage)
        && (contract.startBinding() == null
            || !provider().equals(contract.startBinding().provider())
            || !binding.formId().equals(contract.startBinding().formId())
            || binding.version() != contract.startBinding().version())) {
      throw invalid("审批节点必须引用本流程发起时的业务表单快照版本");
    }
    if ("task".equals(stage)
        && binding.variableMappings().entrySet().stream()
            .anyMatch(
                entry ->
                    !entry
                        .getValue()
                        .equals(contract.startBinding().variableMappings().get(entry.getKey())))) {
      throw invalid("审批场景只能使用发起时已固定的业务字段映射");
    }
    var variables =
        contract.variables().stream()
            .collect(Collectors.toMap(WorkflowFormContract.Variable::name, Function.identity()));
    Map<String, String> types = new LinkedHashMap<>();
    var targets = new java.util.HashSet<String>();
    binding
        .variableMappings()
        .forEach(
            (field, target) -> {
              var variable = variables.get(target);
              if (variable == null
                  || !targets.add(target)
                  || !("start".equals(variable.scope()) || "both".equals(variable.scope()))) {
                throw invalid("表单字段必须映射到当前阶段可用且不重复的变量");
              }
              types.put(field, variable.type());
            });
    invoke(
        () ->
            forms.validateBinding(
                new FormWorkflowBindingCheck(
                    tenantId,
                    binding.formId(),
                    binding.version(),
                    types,
                    binding.sceneId(),
                    stage)));
  }

  @Override
  public void validateSubmission(
      WorkflowFormContract.Binding binding,
      String stage,
      String taskDefinitionKey,
      Map<String, Object> variables) {
    throw invalid("业务表单提交需要引擎生成的流程上下文");
  }

  @Override
  public void validateSubmission(
      WorkflowFormContract.Binding binding,
      String stage,
      String taskDefinitionKey,
      Map<String, Object> variables,
      WorkflowFormContext context) {
    if ("start".equals(stage) && !WorkflowBusinessSubmissionScope.active()) {
      throw new WorkflowException(WorkflowException.Code.FORBIDDEN, "业务表单必须通过已受理的 APP 提交发起流程");
    }
    if (context == null || context.authority() == null) {
      throw invalid("业务表单提交缺少流程上下文");
    }
    invoke(
        () ->
            forms.validateSubmission(
                new FormWorkflowSubmissionCheck(
                    context.tenantId(),
                    context.actorId(),
                    context.definitionId(),
                    context.definitionVersion(),
                    context.processInstanceId(),
                    context.businessKey(),
                    binding.formId(),
                    binding.version(),
                    stage,
                    taskDefinitionKey,
                    variables,
                    context.authority().administrator(),
                    context.authority().resources())));
  }

  private static void invoke(Runnable call) {
    try {
      call.run();
    } catch (org.simplepoint.plugin.app.form.api.error.ApplicationFormException failure) {
      var code =
          switch (failure.errorCode()) {
            case RESOURCE_ACCESS_DENIED -> WorkflowException.Code.FORBIDDEN;
            case PERSISTENCE_FAILED, RECORD_OPERATION_FAILED ->
                WorkflowException.Code.ENGINE_FAILURE;
            default -> WorkflowException.Code.VALIDATION;
          };
      throw new WorkflowException(code, failure.getMessage(), failure);
    }
  }

  private static WorkflowException invalid(String message) {
    return new WorkflowException(WorkflowException.Code.VALIDATION, message);
  }
}
