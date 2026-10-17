package org.simplepoint.plugin.dna.runtime.service.remote;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;

/** Reusable, bounded tenant/policy context for trusted DNA adapters. */
final class DnaInvocationExecutor {
  private final DnaFormRuntimeCallerGuard callerGuard;

  DnaInvocationExecutor(DnaFormRuntimeCallerGuard callerGuard) {
    this.callerGuard = callerGuard;
  }

  <T> T invoke(
      final DnaInvocationContext context, final boolean recordAccess, final Supplier<T> callback) {
    callerGuard.requireTrustedCaller();
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(callback, "callback");
    if (recordAccess && context.resourceCode() == null) {
      throw new IllegalArgumentException("resourceCode is required for record access");
    }
    try (InvocationScope ignored = new InvocationScope(context, !recordAccess)) {
      return callback.get();
    }
  }

  /** Restores the caller policy only for the current synchronous provider invocation. */
  private static final class InvocationScope implements AutoCloseable {

    private final AuthorizationContext previousAuthorization;
    private final String previousResource;

    private InvocationScope(
        final DnaInvocationContext invocation, final boolean serviceManagement) {
      previousAuthorization = AuthorizationContextHolder.getContext();
      previousResource = ResourcePolicyContext.get();
      AuthorizationContext authorization = authorization(invocation, serviceManagement);
      RequestContextHolder.setContext(
          RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
      ResourcePolicyContext.set(invocation.resourceCode());
    }

    @Override
    public void close() {
      if (previousAuthorization == null) {
        RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      } else {
        RequestContextHolder.setContext(
            RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previousAuthorization);
      }
      ResourcePolicyContext.set(previousResource);
    }

    private static AuthorizationContext authorization(
        final DnaInvocationContext invocation, final boolean serviceManagement) {
      AuthorizationContext context = new AuthorizationContext();
      context.setUserId(invocation.actorId());
      // Caller allowlisting and APP domain authorization have already succeeded.
      // Management delegates need service authority; record delegates retain end-user policy.
      context.setIsAdministrator(serviceManagement || invocation.administrator());
      context.setScopeType(AuthorizationScopeType.TENANT);
      context.setResources(invocation.resources());
      Map<String, String> attributes = new HashMap<>();
      attributes.put("X-Tenant-Id", invocation.tenantId());
      if (invocation.organizationId() != null) {
        attributes.put("X-Org-Dept-Id", invocation.organizationId());
      }
      context.setAttributes(attributes);
      context.setDataScopeType(invocation.dataScopeType());
      context.setDeptIds(invocation.departmentIds());
      context.setDataScopeIncludeSelf(invocation.dataScopeIncludeSelf());
      if (invocation.resourceCode() != null) {
        context.setResourceFieldPermissions(
            Map.of(invocation.resourceCode(), invocation.fieldPermissions()));
        if (invocation.dataScopeType() != null) {
          context.setResourceDataScopes(
              Map.of(
                  invocation.resourceCode(),
                  new ResourceDataScope(
                      invocation.dataScopeType(),
                      invocation.departmentIds(),
                      invocation.dataScopeIncludeSelf(),
                      invocation.dataScopeExpressions())));
        }
      }
      return context;
    }
  }
}
