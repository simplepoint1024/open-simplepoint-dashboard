package org.simplepoint.plugin.app.form.service;

import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.plugin.app.form.api.ApplicationWorkbenchResources;
import org.springframework.security.access.AccessDeniedException;

/** Resolves workbench operations from the same effective grants used by method security. */
public final class ApplicationWorkbenchAccess {
  private ApplicationWorkbenchAccess() {}

  public static boolean allowed(String authority) {
    return AuthorizationScopeGuards.hasTenantResourceAction(
        AuthorizationContextHolder.getContext(), ApplicationWorkbenchResources.VIEW, authority);
  }

  public static void require(String authority) {
    if (!allowed(authority)) {
      throw new AccessDeniedException("The active workspace does not grant this workbench operation");
    }
  }
}
