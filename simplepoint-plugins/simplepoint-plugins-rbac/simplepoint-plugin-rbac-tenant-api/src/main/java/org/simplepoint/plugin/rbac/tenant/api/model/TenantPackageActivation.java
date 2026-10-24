package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.Instant;
import java.util.List;

/** Stable receipt: retries return these original results rather than recomputing current balances. */
public record TenantPackageActivation(String instanceId, Instant activatedAt, List<ResourceCredit> resources) {
  /** Keeps the returned committed receipt immutable. */
  public TenantPackageActivation {
    resources = List.copyOf(resources);
  }

  /** Resulting expiry at this activation, not an expiry shared by all resources in a package. */
  public record ResourceCredit(String resourceId, Integer durationDays, Instant expiresAt) {}
}
