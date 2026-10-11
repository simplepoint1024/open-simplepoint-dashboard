package org.simplepoint.plugin.dna.runtime.service.support;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.stereotype.Component;

/** Reads trusted tenant and actor identity from the platform request context. */
@Component
public class RuntimeRequestContext {

  private static final String TENANT_ATTRIBUTE = "X-Tenant-Id";

  /** Returns the current tenant and actor or rejects an incomplete context. */
  public RuntimeActor requireActor() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    String tenantId = context == null ? null : normalize(context.getAttribute(TENANT_ATTRIBUTE));
    if (tenantId == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.TENANT_CONTEXT_REQUIRED,
          "An active tenant context is required"
      );
    }
    String actorId = context == null ? null : normalize(context.getUserId());
    if (actorId == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.ACTOR_CONTEXT_REQUIRED,
          "An authenticated actor is required"
      );
    }
    return new RuntimeActor(tenantId, actorId);
  }

  private static String normalize(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /** Trusted identity for one service invocation. */
  public record RuntimeActor(String tenantId, String actorId) {
  }
}
