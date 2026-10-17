package org.simplepoint.plugin.workflow.service;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.StartProcessCommand;
import org.simplepoint.plugin.workflow.api.service.WorkflowBusinessRuntimeService;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.simplepoint.plugin.workflow.configuration.WorkflowBusinessRemoteProperties;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Restores accepted user authority for one trusted call; never elevates the caller's authority. */
@Service
@RemoteProvider
@EnableConfigurationProperties(WorkflowBusinessRemoteProperties.class)
public class WorkflowBusinessRuntimeServiceImpl implements WorkflowBusinessRuntimeService {
  private final WorkflowCenterService workflow;
  private final Set<String> allowedCallers;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public WorkflowBusinessRuntimeServiceImpl(
      WorkflowCenterService workflow, WorkflowBusinessRemoteProperties properties) {
    this.workflow = workflow;
    this.allowedCallers = Set.copyOf(properties.getAllowedCallers());
  }

  @Override
  public org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult<
          org.simplepoint.plugin.workflow.api.model.WorkflowModels.BoundDefinition>
      boundDefinitions(
          WorkflowInvocationContext context,
          String provider,
          String formId,
          int formVersion,
          String keyword,
          int page,
          int size) {
    return invoke(
        context,
        () -> workflow.boundDefinitions(provider, formId, formVersion, keyword, page, size));
  }

  @Override
  public RuntimeForm taskForm(WorkflowInvocationContext context, String taskId) {
    return invoke(context, () -> workflow.taskForm(taskId));
  }

  @Override
  public RuntimeForm startForm(
      WorkflowInvocationContext context, String definitionId, Integer version) {
    return invoke(context, () -> workflow.startForm(definitionId, version));
  }

  @Override
  public RuntimeForm validateStartForm(
      WorkflowInvocationContext context,
      String definitionId,
      int version,
      Map<String, Object> variables,
      java.util.List<String> ccUserIds) {
    return invoke(
        context, () -> workflow.validateStartForm(definitionId, version, variables, ccUserIds));
  }

  @Override
  public ProcessInstanceSummary start(
      WorkflowInvocationContext context, String definitionId, StartProcessCommand command) {
    return invoke(
        context,
        () ->
            WorkflowBusinessSubmissionScope.call(
                () -> workflow.start(definitionId, reliable(command))));
  }

  @Override
  public ProcessInstanceSummary resubmit(
      WorkflowInvocationContext context, String previousInstanceId, StartProcessCommand command) {
    return invoke(
        context,
        () ->
            WorkflowBusinessSubmissionScope.call(
                () -> workflow.resubmit(previousInstanceId, reliable(command))));
  }

  @Override
  public org.simplepoint.plugin.workflow.api.model.WorkflowResubmission resubmissionForm(
      WorkflowInvocationContext context, String processInstanceId) {
    return invoke(context, () -> workflow.resubmissionForm(processInstanceId));
  }

  @Override
  public ProcessInstanceSummary instance(
      WorkflowInvocationContext context, String processInstanceId) {
    return invoke(context, () -> workflow.progress(processInstanceId).diagram().instance());
  }

  private static StartProcessCommand reliable(StartProcessCommand command) {
    if (command == null
        || command.version() == null
        || command.version() < 1
        || command.businessKey() == null
        || command.businessKey().isBlank()
        || command.idempotencyKey() == null
        || command.idempotencyKey().isBlank()) {
      throw new WorkflowException(
          WorkflowException.Code.VALIDATION,
          "Business submissions require a pinned version, business reference and operation key");
    }
    return command;
  }

  private <T> T invoke(WorkflowInvocationContext invocation, Supplier<T> action) {
    requireCaller();
    java.util.Objects.requireNonNull(invocation, "invocation");
    AuthorizationContext previous = AuthorizationContextHolder.getContext();
    String resource = ResourcePolicyContext.get();
    AuthorizationContext current = new AuthorizationContext();
    current.setScopeType(AuthorizationScopeType.TENANT);
    current.setAttributes(Map.of("X-Tenant-Id", invocation.tenantId()));
    current.setUserId(invocation.actorId());
    current.setIsAdministrator(invocation.administrator());
    current.setResources(invocation.resources());
    current.setRoles(invocation.roles());
    try {
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, current);
      ResourcePolicyContext.set(null);
      return action.get();
    } finally {
      if (previous == null) {
        RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      } else {
        RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
      }
      ResourcePolicyContext.set(resource);
    }
  }

  private void requireCaller() {
    if (!org.simplepoint.core.security.ServiceClientIdentity.allowed(
        SecurityContextHolder.getContext().getAuthentication(), allowedCallers)) {
      throw new WorkflowException(
          WorkflowException.Code.FORBIDDEN, "Caller cannot invoke the workflow business contract");
    }
  }
}
