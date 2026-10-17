package org.simplepoint.plugin.app.form.service;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;

/** Reads trusted tenant and actor identity from the platform request context. */
public class FormRequestContext {

  private static final String TENANT_ATTRIBUTE = "X-Tenant-Id";

  /** Returns the current tenant and actor or rejects an incomplete context. */
  public FormActor requireActor() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String tenantId = context == null ? null : normalize(context.getAttribute(TENANT_ATTRIBUTE));
    String actorId = context == null ? null : normalize(context.getUserId());
    if (tenantId == null) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.TENANT_CONTEXT_REQUIRED,
          "An active tenant context is required"
      );
    }
    if (actorId == null) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.ACTOR_CONTEXT_REQUIRED,
          "An authenticated actor is required"
      );
    }
    return new FormActor(tenantId, actorId);
  }

  private static String normalize(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Trusted identity for one form operation. */
  public record FormActor(String tenantId, String actorId) {
  }
}
