package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.Instant;

/** Stable receipt for an irreversible package revocation, including retries. */
public record TenantPackageRevocation(String instanceId, Instant revokedAt) {}
