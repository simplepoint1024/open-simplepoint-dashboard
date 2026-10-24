package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.Instant;

/** Purchase content, original credit receipt and current aggregate entitlement are deliberately separate. */
public record TenantPackageResourceView(
    String id, String resourceCode, String resourceName, Integer durationDays,
    Instant activatedAt, Instant creditedExpiresAt, Instant revokedAt,
    Instant currentStartsAt, Instant currentExpiresAt, EntitlementState currentState) {

  /** Time entitlement only; role grants and resource availability still govern actual access. */
  public enum EntitlementState { NONE, NOT_STARTED, ACTIVE, UNLIMITED, EXPIRED }

  /** Classifies the half-open interval without treating a missing balance as unlimited. */
  public static EntitlementState stateAt(Instant startsAt, Instant expiresAt, Instant now) {
    if (startsAt == null) {
      return EntitlementState.NONE;
    }
    if (now.isBefore(startsAt)) {
      return EntitlementState.NOT_STARTED;
    }
    if (expiresAt == null) {
      return EntitlementState.UNLIMITED;
    }
    return now.isBefore(expiresAt) ? EntitlementState.ACTIVE : EntitlementState.EXPIRED;
  }
}
