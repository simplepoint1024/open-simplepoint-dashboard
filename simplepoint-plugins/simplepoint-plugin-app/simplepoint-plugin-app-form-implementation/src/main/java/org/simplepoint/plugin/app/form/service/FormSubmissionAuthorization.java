package org.simplepoint.plugin.app.form.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.workflow.api.model.WorkflowInvocationContext;

/**
 * An accepted intent carries bounded authority, never an access token or arbitrary serialized
 * context.
 */
final class FormSubmissionAuthorization implements AutoCloseable {
  private final AuthorizationContext previous = AuthorizationContextHolder.getContext();
  private final String resource = ResourcePolicyContext.get();

  FormSubmissionAuthorization(AcceptedFormSubmission submission) {
    DnaInvocationContext data = submission.dataAuthority();
    var workflow = submission.workflowAuthority();
    if (!data.tenantId().equals(submission.form().tenantId())
        || !data.tenantId().equals(workflow.tenantId())
        || !data.actorId().equals(workflow.actorId())
        || !data.resourceCode().equals(submission.form().resourceCode())) {
      throw new IllegalStateException("Submission authority does not match its accepted form");
    }
    var context = new AuthorizationContext();
    context.setUserId(data.actorId());
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setIsAdministrator(data.administrator());
    context.setResources(data.resources());
    context.setRoles(workflow.roles());
    Map<String, String> attributes = new HashMap<>();
    attributes.put("X-Tenant-Id", data.tenantId());
    if (data.organizationId() != null) {
      attributes.put("X-Org-Dept-Id", data.organizationId());
    }
    context.setAttributes(attributes);
    context.setDataScopeType(data.dataScopeType());
    context.setDeptIds(data.departmentIds());
    context.setDataScopeIncludeSelf(data.dataScopeIncludeSelf());
    context.setResourceFieldPermissions(Map.of(data.resourceCode(), data.fieldPermissions()));
    if (data.dataScopeType() != null) {
      context.setResourceDataScopes(
          Map.of(
              data.resourceCode(),
              new ResourceDataScope(
                  data.dataScopeType(),
                  data.departmentIds(),
                  data.dataScopeIncludeSelf(),
                  data.dataScopeExpressions())));
    }
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    ResourcePolicyContext.set(data.resourceCode());
  }

  static WorkflowInvocationContext workflowContext() {
    var current = AuthorizationContextHolder.getContext();
    if (current == null || current.getScopeType() != AuthorizationScopeType.TENANT) {
      throw new org.simplepoint.plugin.app.form.api.error.ApplicationFormException(
          org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode
              .TENANT_CONTEXT_REQUIRED,
          "An active organization workspace is required");
    }
    return new WorkflowInvocationContext(
        current.getAttribute("X-Tenant-Id"),
        current.getUserId(),
        Boolean.TRUE.equals(current.getIsAdministrator()),
        current.getResources() == null ? Set.of() : Set.copyOf(current.getResources()),
        current.getRoles() == null ? Set.of() : Set.copyOf(current.getRoles()));
  }

  @Override
  public void close() {
    if (previous == null) {
      RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    } else {
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
    }
    ResourcePolicyContext.set(resource);
  }
}
