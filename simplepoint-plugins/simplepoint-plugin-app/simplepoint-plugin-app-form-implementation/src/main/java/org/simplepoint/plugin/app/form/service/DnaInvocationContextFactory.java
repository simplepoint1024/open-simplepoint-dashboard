package org.simplepoint.plugin.app.form.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaResourcePolicy;

/** Captures the current platform authorization policy for one trusted APP-to-DNA call. */
public class DnaInvocationContextFactory {

  private static final String ORGANIZATION_ATTRIBUTE = "X-Org-Dept-Id";

  private final FormRequestContext requestContext;
  private final FormRelationTargetResolver relations;

  /** Creates the factory over the canonical form actor resolver. */
  public DnaInvocationContextFactory(
      final FormRequestContext requestContext, final FormRelationTargetResolver relations) {
    this.requestContext = requestContext;
    this.relations = java.util.Objects.requireNonNull(relations, "relations");
  }

  /**
   * Captures current target policies for writes and accepted submission intents, never for plain
   * lists.
   */
  public DnaInvocationContext forWrite(
      final PublishedApplicationFormResolver.PublishedApplicationForm form) {
    return form.document().relations().isEmpty()
        ? forForm(form)
        : forRelations(form, relations.availableForWrite(form));
  }

  /** Returns an exact target context, or null when a delegated request omitted its own policy. */
  public DnaInvocationContext forRelatedRead(
      final PublishedApplicationFormResolver.PublishedApplicationForm source,
      final PublishedApplicationFormResolver.PublishedApplicationForm target) {
    var combined = forRelations(source, List.of(target));
    if (target.resourceCode().equals(combined.resourceCode())) {
      return combined;
    }
    var policy = combined.additionalResourcePolicies().get(target.resourceCode());
    if (policy == null) {
      return null;
    }
    return new DnaInvocationContext(
        combined.tenantId(),
        combined.actorId(),
        combined.organizationId(),
        target.resourceCode(),
        combined.administrator(),
        combined.resources(),
        policy.dataScopeType(),
        policy.departmentIds(),
        policy.dataScopeIncludeSelf(),
        policy.dataScopeExpressions(),
        policy.fieldPermissions());
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

  /** Applies derived-field restrictions to the trusted policy used for queries and mutations. */
  public DnaInvocationContext forForm(
      final PublishedApplicationFormResolver.PublishedApplicationForm form) {
    var context = forResource(form.resourceCode());
    var permissions = FormCalculationPermissions.restrict(form, context.fieldPermissions());
    if (permissions == context.fieldPermissions()) {
      return context;
    }
    return new DnaInvocationContext(
        context.tenantId(),
        context.actorId(),
        context.organizationId(),
        context.resourceCode(),
        context.administrator(),
        context.resources(),
        context.dataScopeType(),
        context.departmentIds(),
        context.dataScopeIncludeSelf(),
        context.dataScopeExpressions(),
        permissions,
        context.additionalResourcePolicies());
  }

  /** A delegated publication must supply the target's policy, never the initiating page's defaults. */
  DnaInvocationContext forCollectionMigrationRead(
      PublishedApplicationFormResolver.PublishedApplicationForm target) {
    var authorization = AuthorizationContextHolder.getContext();
    if (authorization == null
        || !authorization.isResourcePolicyDefaultsResolved()
            && (resourceScope(authorization, target.resourceCode()) == null
                || resourceFields(authorization, target.resourceCode()) == null)
            && !Boolean.TRUE.equals(authorization.getIsAdministrator())) {
      throw new org.simplepoint.plugin.app.form.api.error.ApplicationFormException(
          org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "The target page's record policy is unavailable");
    }
    return forForm(target);
  }

  /**
   * Captures only server-resolved relation participants. Missing target grants stay absent so an
   * unchanged retained reference need not disclose or reauthorize an inaccessible target.
   */
  public DnaInvocationContext forRelations(
      final PublishedApplicationFormResolver.PublishedApplicationForm source,
      final Collection<PublishedApplicationFormResolver.PublishedApplicationForm> targets) {
    if (targets == null || targets.size() > 50) {
      throw new IllegalArgumentException("Related form budget exceeded");
    }
    var actor = requestContext.requireActor();
    if (!actor.tenantId().equals(source.tenantId())) {
      throw new IllegalArgumentException("Related forms must share the active tenant");
    }
    var primary = forForm(source);
    var authorization = AuthorizationContextHolder.getContext();
    Map<String, String> primaryFields = primary.fieldPermissions();
    Map<String, DnaResourcePolicy> policies = new TreeMap<>();
    Map<String, PublishedApplicationFormResolver.PublishedApplicationForm> identities =
        new HashMap<>();
    identities.put(source.resourceCode(), source);
    for (var target : targets) {
      if (!actor.tenantId().equals(target.tenantId())) {
        throw new IllegalArgumentException("Related forms must share the active tenant");
      }
      var prior = identities.putIfAbsent(target.resourceCode(), target);
      if (prior != null) {
        if (!prior.applicationId().equals(target.applicationId())
            || !prior.formId().equals(target.formId())
            || !prior.physicalModelId().equals(target.physicalModelId())) {
          throw new IllegalArgumentException("Related resource has conflicting form identities");
        }
      }
      if (!primary.administrator() && !primary.resources().contains(target.resourceCode())) {
        continue;
      }
      if (!target.resourceCode().equals(primary.resourceCode())
          && !authorization.isResourcePolicyDefaultsResolved()
          && (resourceScope(authorization, target.resourceCode()) == null
              || resourceFields(authorization, target.resourceCode()) == null)) {
        // RPC/submission contexts may contain all grant names but only a subset of page policies.
        // Missing target policy cannot be reconstructed from the source page's top-level scope.
        continue;
      }
      var selected = forForm(target);
      if (target.resourceCode().equals(primary.resourceCode())) {
        primaryFields = restrictTogether(primaryFields, selected.fieldPermissions());
        continue;
      }
      var previous = policies.get(target.resourceCode());
      var targetFields =
          previous == null
              ? selected.fieldPermissions()
              : restrictTogether(previous.fieldPermissions(), selected.fieldPermissions());
      policies.put(
          target.resourceCode(),
          new DnaResourcePolicy(
              "1",
              selected.dataScopeType() == null
                  ? selected.administrator() ? "ALL" : "DENY"
                  : selected.dataScopeType(),
              selected.departmentIds(),
              selected.dataScopeIncludeSelf(),
              selected.dataScopeExpressions(),
              targetFields));
    }
    return new DnaInvocationContext(
        primary.tenantId(),
        primary.actorId(),
        primary.organizationId(),
        primary.resourceCode(),
        primary.administrator(),
        primary.resources(),
        primary.dataScopeType() == null
            ? primary.administrator() ? "ALL" : "DENY"
            : primary.dataScopeType(),
        primary.departmentIds(),
        primary.dataScopeIncludeSelf(),
        primary.dataScopeExpressions(),
        primaryFields,
        policies);
  }

  private static Map<String, String> restrictTogether(
      Map<String, String> left, Map<String, String> right) {
    var result = new HashMap<>(left);
    var order = List.of("HIDDEN", "MASKED", "VISIBLE", "EDITABLE");
    right.forEach(
        (field, access) ->
            result.merge(
                field,
                access,
                (first, second) -> {
                  int a = order.indexOf(first);
                  int b = order.indexOf(second);
                  if (a < 0 || b < 0) {
                    throw new IllegalArgumentException("Invalid related field access");
                  }
                  return a <= b ? first : second;
                }));
    return Map.copyOf(result);
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
      final String resourceCode, final boolean includeFieldPermissions) {
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
            ? Set.of()
            : Set.copyOf(authorization.getResources()),
        scope == null ? dataScopeType(authorization) : scope.type(),
        scope == null ? departmentIds(authorization) : scope.departmentIds(),
        scope == null ? includeSelf(authorization) : scope.includeSelf(),
        scope == null ? List.of() : scope.expressions(),
        includeFieldPermissions && fieldPermissions != null ? fieldPermissions : Map.of());
  }

  private static ResourceDataScope resourceScope(
      final AuthorizationContext context, final String resourceCode) {
    if (context == null || resourceCode == null || context.getResourceDataScopes() == null) {
      return null;
    }
    return context.getResourceDataScopes().get(resourceCode);
  }

  static Map<String, String> resourceFields(
      final AuthorizationContext context, final String resourceCode) {
    if (context == null || resourceCode == null || context.getResourceFieldPermissions() == null) {
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
