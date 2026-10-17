package org.simplepoint.plugin.app.form.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;

/** Captures the current platform authorization policy for one trusted APP-to-DNA call. */
public class DnaInvocationContextFactory {

  private static final String ORGANIZATION_ATTRIBUTE = "X-Org-Dept-Id";

  private final FormRequestContext requestContext;

  /** Creates the factory over the canonical form actor resolver. */
  public DnaInvocationContextFactory(final FormRequestContext requestContext) {
    this.requestContext = requestContext;
  }

  /** Produces a bounded immutable snapshot; DNA revalidates its model and resource bindings. */
  public DnaInvocationContext current() {
    return snapshot(ResourcePolicyContext.get(), true);
  }

  /** Produces policy for a server-resolved dynamic APP page resource. */
  public DnaInvocationContext forResource(final String resourceCode) {
    if (resourceCode == null || resourceCode.isBlank() || resourceCode.length() > 200) {
      throw new IllegalArgumentException("resourceCode is required");
    }
    return snapshot(resourceCode.trim(), true);
  }

  /**
   * Produces the same page and row scope while allowing server-only invariant validation to read
   * the complete record. The returned context must never be used to render a response or mutate.
   */
  DnaInvocationContext forResourceValidation(final String resourceCode) {
    if (resourceCode == null || resourceCode.isBlank() || resourceCode.length() > 200) {
      throw new IllegalArgumentException("resourceCode is required");
    }
    return snapshot(resourceCode.trim(), false);
  }

  private DnaInvocationContext snapshot(
      final String resourceCode,
      final boolean includeFieldPermissions
  ) {
    FormRequestContext.FormActor actor = requestContext.requireActor();
    AuthorizationContext authorization = AuthorizationContextHolder.getContext();
    ResourceDataScope scope = resourceScope(authorization, resourceCode);
    Map<String, String> fieldPermissions = resourceFields(authorization, resourceCode);
    return new DnaInvocationContext(
        actor.tenantId(),
        actor.actorId(),
        authorization == null ? null : authorization.getAttribute(ORGANIZATION_ATTRIBUTE),
        resourceCode,
        authorization != null && Boolean.TRUE.equals(authorization.getIsAdministrator()),
        authorization == null || authorization.getResources() == null
            ? Set.of() : Set.copyOf(authorization.getResources()),
        scope == null ? dataScopeType(authorization) : scope.type(),
        scope == null ? departmentIds(authorization) : scope.departmentIds(),
        scope == null ? includeSelf(authorization) : scope.includeSelf(),
        scope == null ? List.of() : scope.expressions(),
        includeFieldPermissions && fieldPermissions != null ? fieldPermissions : Map.of()
    );
  }

  private static ResourceDataScope resourceScope(
      final AuthorizationContext context,
      final String resourceCode
  ) {
    if (context == null || resourceCode == null || context.getResourceDataScopes() == null) {
      return null;
    }
    return context.getResourceDataScopes().get(resourceCode);
  }

  private static Map<String, String> resourceFields(
      final AuthorizationContext context,
      final String resourceCode
  ) {
    if (context == null || resourceCode == null
        || context.getResourceFieldPermissions() == null) {
      return null;
    }
    return context.getResourceFieldPermissions().get(resourceCode);
  }

  private static String dataScopeType(final AuthorizationContext context) {
    return context == null ? null : context.getDataScopeType();
  }

  private static Set<String> departmentIds(final AuthorizationContext context) {
    return context == null || context.getDeptIds() == null ? Set.of() : context.getDeptIds();
  }

  private static boolean includeSelf(final AuthorizationContext context) {
    return context != null && Boolean.TRUE.equals(context.getDataScopeIncludeSelf());
  }
}
