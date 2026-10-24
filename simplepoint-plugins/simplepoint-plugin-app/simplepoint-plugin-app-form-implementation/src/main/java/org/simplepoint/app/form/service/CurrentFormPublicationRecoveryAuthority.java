package org.simplepoint.app.form.service;

import java.util.HashMap;
import java.util.Objects;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.FormPublicationRecoveryAuthority;
import org.simplepoint.security.ResourceScopeCatalog;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.context.AuthorizationContextService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * Uses fresh platform authorization for each background publication attempt, without cached user
 * grants.
 */
@Component
public class CurrentFormPublicationRecoveryAuthority implements FormPublicationRecoveryAuthority {
  private static final String PAGE = "app.workbench.view";
  private static final String PUBLISH = "app.workbench.forms.publish";
  private final AuthorizationContextService authorization;
  private final ResourceScopeCatalog scopes;

  /** Uses the same authorization and scope contracts as interactive publication. */
  public CurrentFormPublicationRecoveryAuthority(
      final AuthorizationContextService authorization, final ResourceScopeCatalog scopes) {
    this.authorization = authorization;
    this.scopes = scopes;
  }

  @Override
  public Scope open(final String tenantId, final String actorId, final String roleId) {
    if (tenantId == null || tenantId.isBlank() || actorId == null || actorId.isBlank()) {
      throw forbidden();
    }
    var attributes = new HashMap<String, String>();
    attributes.put("X-Tenant-Id", tenantId);
    attributes.put("X-User-Id", actorId);
    if (roleId != null) {
      attributes.put("X-Role-Id", roleId);
    }
    final AuthorizationContext current;
    try {
      current = authorization.calculate(tenantId, actorId, null, attributes);
    } catch (AccessDeniedException | BadCredentialsException denied) {
      throw forbidden();
    }
    if (current == null
        || !actorId.equals(current.getUserId())
        || !tenantId.equals(current.getAttribute("X-Tenant-Id"))
        || !Objects.equals(roleId, current.getAttribute("X-Role-Id"))
        || !allowed(current, PAGE)
        || !allowed(current, PUBLISH)) {
      throw forbidden();
    }
    var previous = AuthorizationContextHolder.getContext();
    String resource = ResourcePolicyContext.get();
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, current);
    ResourcePolicyContext.set(PAGE);
    return () -> {
      if (previous == null) {
        RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
      } else {
        RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, previous);
      }
      ResourcePolicyContext.set(resource);
    };
  }

  private boolean allowed(final AuthorizationContext context, final String resource) {
    return scopes
            .findScopes(resource)
            .map(declared -> ResourceScopePolicy.isAccessible(declared, context))
            .orElse(false)
        && context.asAuthorities().stream()
            .anyMatch(
                authority ->
                    "ROLE_Administrator".equals(authority.getAuthority())
                        || resource.equals(authority.getAuthority()));
  }

  private static ApplicationFormException forbidden() {
    return new ApplicationFormException(
        ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
        "Current publication authority is unavailable; an authorized operator must resume the"
            + " original release");
  }
}
